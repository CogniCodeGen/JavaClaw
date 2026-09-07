package com.javaclaw.server.persistence;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import com.javaclaw.api.AgentTurn;
import com.javaclaw.api.ConversationThread;
import com.javaclaw.api.ManagedWorktree;
import com.javaclaw.api.ManagedWorktreeArtifact;
import com.javaclaw.api.ManagedWorktreeState;
import com.javaclaw.api.SandboxExecutor;
import com.javaclaw.api.ThreadExecutionIntent;
import com.javaclaw.api.ThreadId;
import com.javaclaw.api.TurnId;
import com.javaclaw.api.TurnStatus;
import com.javaclaw.api.Workspace;
import com.javaclaw.api.WorkspaceId;
import com.javaclaw.api.WorktreeId;
import com.javaclaw.protocol.CanonicalJson;

/** 父子 Thread 绑定与 Managed Worktree 生命周期入口。 */
public final class ManagedWorktreeService {
    private final H2Transactions transactions;
    private final ThreadRepository threads = new ThreadRepository();
    private final TurnRepository turns = new TurnRepository();
    private final ManagedWorktreeRepository worktrees = new ManagedWorktreeRepository();
    private final IdempotencyRepository idempotency = new IdempotencyRepository();
    private final ManagedGitWorktreeRunner git;
    private final ManagedWorktreePaths paths;
    private final ManagedWorktreeArtifactService artifacts;
    private final ManagedWorktreeApplyService apply;
    private final ManagedWorktreeBindingResolver bindingResolver;
    private final CanonicalJson json;
    private final Clock clock;

    /**
     * 创建 Managed Worktree 服务。
     *
     * @param database data-v6 数据库
     * @param attachments 内容寻址附件服务
     * @param json 共享 JSON codec
     * @param clock 平台时钟
     * @param sandbox Native Host Sandbox
     */
    public ManagedWorktreeService(
            H2Database database,
            AttachmentService attachments,
            CanonicalJson json,
            Clock clock,
            SandboxExecutor sandbox) {
        this(database, attachments, json, clock, sandbox, (worktree, isolationRoot) -> {});
    }

    ManagedWorktreeService(
            H2Database database,
            AttachmentService attachments,
            CanonicalJson json,
            Clock clock,
            SandboxExecutor sandbox,
            ManagedWorktreeArtifactService.CleanupIsolationObserver cleanupObserver) {
        H2Database checkedDatabase = Objects.requireNonNull(database, "database");
        transactions = new H2Transactions(checkedDatabase);
        this.json = Objects.requireNonNull(json, "json");
        this.clock = Objects.requireNonNull(clock, "clock");
        git = new ManagedGitWorktreeRunner(sandbox);
        paths = new ManagedWorktreePaths(checkedDatabase.dataRoot());
        bindingResolver = new ManagedWorktreeBindingResolver(checkedDatabase);
        artifacts = new ManagedWorktreeArtifactService(
                checkedDatabase, attachments, git, paths, this.json, this.clock, cleanupObserver);
        apply = new ManagedWorktreeApplyService(checkedDatabase, attachments, git, paths, this.json, this.clock);
    }

    /**
     * 列出 Workspace 的受管 Worktree。
     *
     * @param workspaceId Workspace
     * @param includeCleaned 是否包含已清理记录
     * @return 稳定排序快照
     */
    public List<ManagedWorktree> list(WorkspaceId workspaceId, boolean includeCleaned) {
        return execute(connection ->
                worktrees.list(connection, Objects.requireNonNull(workspaceId, "workspaceId"), includeCleaned));
    }

    /**
     * 读取 Worktree。
     *
     * @param id Worktree
     * @return 当前快照
     */
    public ManagedWorktree read(WorktreeId id) {
        return find(Objects.requireNonNull(id, "worktreeId"));
    }

    /**
     * 读取子 Thread 的执行根；没有受管绑定时为空。
     *
     * @param childThreadId 子 Thread
     * @return 未清理 Worktree
     */
    public Optional<ManagedWorktree> findByChild(ThreadId childThreadId) {
        return execute(connection ->
                        worktrees.findByChild(connection, Objects.requireNonNull(childThreadId, "childThreadId")))
                .filter(worktree -> worktree.state() != ManagedWorktreeState.CLEANED);
    }

    /**
     * 读取写型子 Thread 的可执行 Worktree，并拒绝应用、清理或结果不明状态。
     *
     * @param childThreadId 写型子 Thread
     * @return 服务端权威 Worktree
     */
    public ManagedWorktree requireForExecution(ThreadId childThreadId) {
        ManagedWorktree current = findByChild(Objects.requireNonNull(childThreadId, "childThreadId"))
                .orElseThrow(() -> PersistenceException.invalidRequest("写型子 Thread 尚未创建 Managed Worktree"));
        paths.requireManaged(current);
        ManagedWorktreePolicy.requireExecutable(current);
        return current;
    }

    /** 启动时补齐已提交但尚未 provision 的写型子 Thread。 */
    public void reconcileProvisioning() {
        List<ConversationThread> isolated =
                execute(connection -> threads.listByExecutionIntent(connection, ThreadExecutionIntent.ISOLATED_WRITE));
        for (ConversationThread thread : isolated) {
            if (findByChild(thread.id()).isPresent()) {
                continue;
            }
            ThreadId parent = thread.parentThreadId().orElseThrow();
            ReconciliationFingerprint fingerprint =
                    new ReconciliationFingerprint(thread.workspaceId(), parent, thread.id());
            CommandIdentity identity = new CommandIdentity(
                    "worktree/provision",
                    "worktree-reconcile:" + thread.id(),
                    0,
                    json.encode(fingerprint).sha256());
            provisionForChild(identity, thread.workspaceId(), parent, thread.id());
        }
    }

    /**
     * 为已经持久化的写型子 Thread 自动创建受管 Worktree。
     *
     * @param identity 内部幂等身份，expected revision 必须为 0
     * @param workspaceId Workspace
     * @param parentThreadId 父 Thread
     * @param childThreadId 子 Thread
     * @return READY 快照
     */
    public ManagedWorktree provisionForChild(
            CommandIdentity identity, WorkspaceId workspaceId, ThreadId parentThreadId, ThreadId childThreadId) {
        CommandIdentity checked = ManagedWorktreePolicy.requireCreate(identity, "worktree/provision");
        WorktreeId id = ManagedWorktreePolicy.deterministicId(childThreadId);
        synchronized (ManagedWorktreePolicy.resourceLock(id)) {
            Optional<ManagedWorktree> recovered = recover(checked, ManagedWorktree.class);
            if (recovered.isPresent()) {
                return recovered.orElseThrow();
            }
            ManagedWorktreeBindingResolver.Binding binding =
                    bindingResolver.require(workspaceId, parentThreadId, childThreadId);
            return provision(checked, id, binding);
        }
    }

    /**
     * 请求中断子 Thread，并在同一 H2 事务持久化 Turn 取消请求。
     *
     * @param identity 幂等身份与 Worktree expected revision
     * @param id Worktree
     * @param reason 脱敏原因
     * @return Worktree 与需要通知进程内调度器的 Turn
     */
    public ManagedWorktreeInterruptResult interrupt(CommandIdentity identity, WorktreeId id, String reason) {
        CommandIdentity checked = Objects.requireNonNull(identity, "identity");
        WorktreeId checkedId = Objects.requireNonNull(id, "worktreeId");
        String checkedReason = ManagedWorktreePolicy.requireReason(reason);
        synchronized (ManagedWorktreePolicy.resourceLock(checkedId)) {
            Optional<ManagedWorktreeInterruptResult> recovered = recover(checked, ManagedWorktreeInterruptResult.class);
            return recovered.orElseGet(
                    () -> execute(connection -> interrupt(connection, checked, checkedId, checkedReason)));
        }
    }

    /** 导出完整有界 Patch Attachment，且不修改用户真实 Git index。 */
    public ManagedWorktreeArtifact exportPatch(CommandIdentity identity, WorktreeId id) {
        return artifacts.exportPatch(identity, id);
    }

    /** 创建并回读校验 cleanup 所需 Backup。 */
    public ManagedWorktreeArtifact backup(CommandIdentity identity, WorktreeId id) {
        return artifacts.backup(identity, id);
    }

    /** 清理已经稳定备份且没有活动 Turn 的 Worktree。 */
    public ManagedWorktree cleanup(CommandIdentity identity, WorktreeId id) {
        return artifacts.cleanup(identity, id);
    }

    /**
     * 由父 Turn 的受治理工具应用精确 Patch；管理 RPC 不调用此入口。
     *
     * @param idempotencyKey 工具副作用恢复键
     * @param parentTurnId 调用方父 Turn
     * @param id Worktree
     * @param patchDigest 已导出 Patch Attachment 摘要
     * @return APPLIED 快照
     */
    public ManagedWorktree apply(String idempotencyKey, TurnId parentTurnId, WorktreeId id, String patchDigest) {
        return apply.apply(idempotencyKey, parentTurnId, id, patchDigest);
    }

    /** 标记子 Thread 已进入执行；没有 Worktree 的只读 Thread 不产生状态。 */
    public void markRunning(ThreadId childThreadId) {
        Optional<ManagedWorktree> found = findByChild(childThreadId);
        if (found.isEmpty()) {
            return;
        }
        synchronized (ManagedWorktreePolicy.resourceLock(found.orElseThrow().id())) {
            execute(connection -> {
                ManagedWorktree current =
                        worktrees.lock(connection, found.orElseThrow().id());
                if (current.state() == ManagedWorktreeState.RUNNING) {
                    return null;
                }
                if (!ManagedWorktreePolicy.mayStartExecution(current)) {
                    throw PersistenceException.invalidRequest("当前 Worktree 状态不能启动 Turn: " + current.state());
                }
                worktrees.transition(connection, current, ManagedWorktreeState.RUNNING, current.backup(), now());
                return null;
            });
        }
    }

    /** 根据 Turn 终态更新绑定 Worktree；不会覆盖冲突、应用或清理状态。 */
    public void finishExecution(ThreadId childThreadId, TurnStatus status) {
        ManagedWorktreeState target =
                switch (Objects.requireNonNull(status, "status")) {
                    case COMPLETED -> ManagedWorktreeState.COMPLETED;
                    case CANCELLED -> ManagedWorktreeState.INTERRUPTED;
                    case FAILED -> ManagedWorktreeState.FAILED;
                    case QUEUED, RUNNING, WAITING -> throw new IllegalArgumentException("Turn 尚未进入终态");
                };
        transitionForChild(childThreadId, ManagedWorktreeState.RUNNING, target);
    }

    private ManagedWorktree provision(
            CommandIdentity identity, WorktreeId id, ManagedWorktreeBindingResolver.Binding binding) {
        Path destination = paths.destination(binding.workspace().id(), id);
        Optional<ManagedWorktree> existing = execute(
                connection -> worktrees.findByChild(connection, binding.child().id()));
        if (existing.isPresent()) {
            ManagedWorktree current = existing.orElseThrow();
            ManagedWorktreeBindingResolver.requireSame(current, binding, destination);
            return persistProvision(identity, current);
        }
        String baseCommit = prepareDirectory(binding.workspace(), destination);
        Instant now = now();
        ManagedWorktree created = new ManagedWorktree(
                id,
                binding.workspace().id(),
                binding.parent().id(),
                binding.child().id(),
                destination,
                baseCommit,
                ManagedWorktreeState.READY,
                1,
                Optional.empty(),
                now,
                now);
        return persistProvision(identity, created);
    }

    private String prepareDirectory(Workspace workspace, Path destination) {
        if (git.isRegistered(workspace.root(), destination)) {
            requireRegisteredDirectory(destination);
            return git.baseCommit(destination, paths.root());
        }
        requireUnusedDestination(destination);
        String baseCommit = git.baseCommit(workspace.root(), paths.root());
        git.create(workspace.root(), destination, baseCommit);
        requireRegisteredDirectory(destination);
        return baseCommit;
    }

    private ManagedWorktree persistProvision(CommandIdentity identity, ManagedWorktree created) {
        return execute(connection -> {
            Optional<IdempotencyRepository.StoredCommand> stored =
                    idempotency.find(connection, identity.idempotencyKey());
            if (stored.isPresent()) {
                return recover(identity, stored.orElseThrow(), ManagedWorktree.class);
            }
            Optional<ManagedWorktree> current = worktrees.findByChild(connection, created.childThreadId());
            ManagedWorktree result = current.orElse(created);
            if (current.isEmpty()) {
                worktrees.insert(connection, created);
            }
            idempotency.insert(connection, identity, json.encode(result), now());
            return result;
        });
    }

    private ManagedWorktreeInterruptResult interrupt(
            java.sql.Connection connection, CommandIdentity identity, WorktreeId id, String reason)
            throws java.sql.SQLException {
        ManagedWorktree current = worktrees.lock(connection, id);
        ManagedWorktreePolicy.requireRevision(identity, current);
        if (current.state() != ManagedWorktreeState.READY && current.state() != ManagedWorktreeState.RUNNING) {
            throw PersistenceException.invalidRequest("当前 Worktree 状态不能中断: " + current.state());
        }
        Optional<AgentTurn> active = turns.findActiveByThread(connection, current.childThreadId());
        if (active.isPresent()) {
            AgentTurn turn = active.orElseThrow();
            turns.requestCancellation(connection, turn.id(), turn.revision(), reason, now());
        }
        ManagedWorktree updated =
                worktrees.transition(connection, current, ManagedWorktreeState.INTERRUPTED, current.backup(), now());
        ManagedWorktreeInterruptResult result = new ManagedWorktreeInterruptResult(updated, active.map(AgentTurn::id));
        idempotency.insert(connection, identity, json.encode(result), now());
        return result;
    }

    private void transitionForChild(
            ThreadId childThreadId, ManagedWorktreeState expected, ManagedWorktreeState target) {
        Optional<ManagedWorktree> found = findByChild(childThreadId);
        if (found.isEmpty()) {
            return;
        }
        synchronized (ManagedWorktreePolicy.resourceLock(found.orElseThrow().id())) {
            execute(connection -> {
                ManagedWorktree current =
                        worktrees.lock(connection, found.orElseThrow().id());
                if (current.state() == expected) {
                    worktrees.transition(connection, current, target, current.backup(), now());
                }
                return null;
            });
        }
    }

    private ManagedWorktree find(WorktreeId id) {
        return execute(connection -> worktrees
                .find(connection, id)
                .orElseThrow(() -> PersistenceException.invalidRequest("Managed Worktree 不存在")));
    }

    private static void requireUnusedDestination(Path destination) {
        if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
            throw new PersistenceException("Worktree 目标目录已存在但未注册");
        }
    }

    private static void requireRegisteredDirectory(Path destination) {
        if (Files.isSymbolicLink(destination) || !Files.isDirectory(destination, LinkOption.NOFOLLOW_LINKS)) {
            throw new PersistenceException("Git 未创建预期的 Managed Worktree 目录");
        }
    }

    private <T> Optional<T> recover(CommandIdentity identity, Class<T> type) {
        return execute(connection ->
                idempotency.find(connection, identity.idempotencyKey()).map(stored -> recover(identity, stored, type)));
    }

    private <T> T recover(CommandIdentity identity, IdempotencyRepository.StoredCommand stored, Class<T> type) {
        if (!stored.method().equals(identity.method())
                || !stored.requestDigest().equals(identity.requestDigest())) {
            throw PersistenceException.idempotencyConflict("幂等键已被不同 Worktree 命令使用");
        }
        return json.decode(stored.response(), type);
    }

    private Instant now() {
        return Instant.now(clock);
    }

    private <T> T execute(H2Transactions.SqlWork<T> work) {
        try {
            return transactions.execute(work);
        } catch (RuntimeException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new PersistenceException("Managed Worktree 事务失败", failure);
        }
    }

    private record ReconciliationFingerprint(
            WorkspaceId workspaceId, ThreadId parentThreadId, ThreadId childThreadId) {}
}
