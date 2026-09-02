package com.javaclaw.server.persistence;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

import com.javaclaw.api.AttachmentContent;
import com.javaclaw.api.AttachmentScope;
import com.javaclaw.api.ConversationThread;
import com.javaclaw.api.ManagedWorktree;
import com.javaclaw.api.ManagedWorktreeState;
import com.javaclaw.api.ThreadId;
import com.javaclaw.api.TurnId;
import com.javaclaw.api.Workspace;
import com.javaclaw.api.WorkspaceId;
import com.javaclaw.api.WorktreeId;
import com.javaclaw.protocol.CanonicalJson;

/** 父 Turn 专用的受治理 Patch 应用协调器；管理 RPC 不暴露此能力。 */
final class ManagedWorktreeApplyService {
    private static final String METHOD = "tool/worktree_apply";
    private static final String PATCH_MEDIA_TYPE = "application/vnd.javaclaw.git-patch";

    private final H2Transactions transactions;
    private final WorkspaceRepository workspaces = new WorkspaceRepository();
    private final TurnRepository turns = new TurnRepository();
    private final ThreadRepository threads = new ThreadRepository();
    private final ManagedWorktreeRepository worktrees = new ManagedWorktreeRepository();
    private final IdempotencyRepository idempotency = new IdempotencyRepository();
    private final AttachmentService attachments;
    private final ManagedGitWorktreeRunner git;
    private final ManagedWorktreePaths paths;
    private final CanonicalJson json;
    private final Clock clock;

    ManagedWorktreeApplyService(
            H2Database database,
            AttachmentService attachments,
            ManagedGitWorktreeRunner git,
            ManagedWorktreePaths paths,
            CanonicalJson json,
            Clock clock) {
        transactions = new H2Transactions(Objects.requireNonNull(database, "database"));
        this.attachments = Objects.requireNonNull(attachments, "attachments");
        this.git = Objects.requireNonNull(git, "git");
        this.paths = Objects.requireNonNull(paths, "paths");
        this.json = Objects.requireNonNull(json, "json");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    ManagedWorktree apply(String idempotencyKey, TurnId parentTurnId, WorktreeId worktreeId, String patchDigest) {
        ApplyFingerprint fingerprint = new ApplyFingerprint(
                Objects.requireNonNull(parentTurnId, "parentTurnId"),
                Objects.requireNonNull(worktreeId, "worktreeId"),
                checkedDigest(patchDigest));
        CommandIdentity identity = new CommandIdentity(
                METHOD, idempotencyKey, 0, json.encode(fingerprint).sha256());
        ManagedWorktree initial = find(worktreeId);
        synchronized (ManagedWorktreePolicy.workspaceLock(initial.workspaceId())) {
            synchronized (ManagedWorktreePolicy.resourceLock(worktreeId)) {
                return applyLocked(identity, fingerprint);
            }
        }
    }

    private ManagedWorktree applyLocked(CommandIdentity identity, ApplyFingerprint fingerprint) {
        Optional<ManagedWorktree> recovered = recover(identity);
        if (recovered.isPresent()) {
            return recovered.orElseThrow();
        }
        ManagedWorktree current = find(fingerprint.worktreeId());
        requireAuthority(current, fingerprint.parentTurnId());
        ManagedWorktreePolicy.requireApplicable(current);
        byte[] patch = verifiedPatch(current, fingerprint.patchDigest());
        ManagedWorktree intent = beginApply(current, fingerprint.parentTurnId());
        try {
            requireUnchangedSource(intent, patch);
        } catch (RuntimeException failure) {
            markFailure(intent, ManagedWorktreeState.FAILED, failure);
            throw failure;
        }
        try {
            Workspace workspace = requireGitWorkspace(intent.workspaceId());
            git.apply(applyTarget(workspace, intent.parentThreadId()), paths.root(), patch);
            return finishApply(identity, intent);
        } catch (ManagedGitWorktreeRunner.ApplyRejectedException conflict) {
            markFailure(intent, ManagedWorktreeState.CONFLICTED, conflict);
            throw PersistenceException.revisionConflict("Patch 与父 Workspace 冲突，未写入任何文件");
        } catch (RuntimeException failure) {
            markFailure(intent, ManagedWorktreeState.UNKNOWN_OUTCOME, failure);
            throw failure;
        }
    }

    private byte[] verifiedPatch(ManagedWorktree current, String digest) {
        AttachmentContent stored = attachments.read(AttachmentScope.workspace(current.workspaceId()), digest);
        if (!PATCH_MEDIA_TYPE.equals(stored.metadata().mediaType())) {
            throw PersistenceException.invalidRequest("worktree_apply 只能读取 Patch Attachment");
        }
        byte[] patch = stored.content();
        byte[] live = currentPatch(current);
        if (!java.security.MessageDigest.isEqual(patch, live)) {
            throw PersistenceException.revisionConflict("Patch 摘要不再对应 Worktree 当前内容");
        }
        return patch;
    }

    private ManagedWorktree beginApply(ManagedWorktree expected, TurnId parentTurnId) {
        return execute(connection -> {
            ManagedWorktree current = worktrees.lock(connection, expected.id());
            if (current.revision() != expected.revision()) {
                throw PersistenceException.revisionConflict("Managed Worktree revision 已改变");
            }
            requireAuthority(connection, current, parentTurnId);
            ManagedWorktreePolicy.requireApplicable(current);
            if (turns.findActiveByThread(connection, current.childThreadId()).isPresent()) {
                throw PersistenceException.invalidRequest("子 Thread 仍有活动 Turn，拒绝应用 Patch");
            }
            return worktrees.transition(connection, current, ManagedWorktreeState.APPLYING, current.backup(), now());
        });
    }

    private void requireUnchangedSource(ManagedWorktree intent, byte[] patch) {
        if (!java.security.MessageDigest.isEqual(patch, currentPatch(intent))) {
            throw PersistenceException.revisionConflict("Worktree 在应用意图提交后发生变化");
        }
    }

    private ManagedWorktree finishApply(CommandIdentity identity, ManagedWorktree intent) {
        return execute(connection -> {
            ManagedWorktree current = worktrees.lock(connection, intent.id());
            if (current.state() != ManagedWorktreeState.APPLYING || current.revision() != intent.revision()) {
                throw PersistenceException.revisionConflict("worktree_apply 意图 revision 已改变");
            }
            ManagedWorktree applied =
                    worktrees.transition(connection, current, ManagedWorktreeState.APPLIED, current.backup(), now());
            idempotency.insert(connection, identity, json.encode(applied), now());
            return applied;
        });
    }

    private void markFailure(ManagedWorktree intent, ManagedWorktreeState state, RuntimeException original) {
        try {
            execute(connection -> {
                ManagedWorktree current = worktrees.lock(connection, intent.id());
                if (current.state() == ManagedWorktreeState.APPLYING) {
                    worktrees.transition(connection, current, state, current.backup(), now());
                }
                return null;
            });
        } catch (RuntimeException transitionFailure) {
            original.addSuppressed(transitionFailure);
        }
    }

    private byte[] currentPatch(ManagedWorktree current) {
        paths.requireManaged(current);
        Workspace workspace = requireGitWorkspace(current.workspaceId());
        if (!git.isRegistered(workspace.root(), current.executionRoot())) {
            throw new PersistenceException("Managed Worktree 未在 Git 中注册");
        }
        return git.patch(workspace.root(), current.executionRoot(), paths.scratch(current), current.baseCommit());
    }

    private Workspace requireGitWorkspace(WorkspaceId id) {
        Workspace workspace = execute(connection -> workspaces
                .find(connection, id)
                .orElseThrow(() -> PersistenceException.invalidRequest("Workspace 不存在")));
        if (!Files.exists(workspace.root().resolve(".git"), LinkOption.NOFOLLOW_LINKS)) {
            throw PersistenceException.invalidRequest("Workspace 不是 Git checkout");
        }
        return workspace;
    }

    private Path applyTarget(Workspace workspace, ThreadId parentThreadId) {
        ConversationThread parent = execute(connection -> threads.find(connection, parentThreadId)
                .orElseThrow(() -> PersistenceException.invalidRequest("父 Thread 不存在")));
        if (!parent.workspaceId().equals(workspace.id())) {
            throw new IllegalStateException("父 Thread 与 Worktree Workspace 不一致");
        }
        return switch (parent.executionIntent()) {
            case WORKSPACE -> workspace.root();
            case READ_ONLY -> throw PersistenceException.invalidRequest("只读父 Thread 不能应用 Worktree Patch");
            case ISOLATED_WRITE -> parentWorktree(parent).executionRoot();
        };
    }

    private ManagedWorktree parentWorktree(ConversationThread parent) {
        ManagedWorktree current = execute(connection -> worktrees
                .findByChild(connection, parent.id())
                .orElseThrow(() -> PersistenceException.invalidRequest("父 Thread 的 Managed Worktree 不存在")));
        paths.requireManaged(current);
        ManagedWorktreePolicy.requireExecutable(current);
        return current;
    }

    private ManagedWorktree find(WorktreeId id) {
        return execute(connection -> worktrees
                .find(connection, id)
                .orElseThrow(() -> PersistenceException.invalidRequest("Managed Worktree 不存在")));
    }

    private Optional<ManagedWorktree> recover(CommandIdentity identity) {
        return execute(connection -> idempotency
                .find(connection, identity.idempotencyKey())
                .map(stored -> {
                    if (!stored.method().equals(identity.method())
                            || !stored.requestDigest().equals(identity.requestDigest())) {
                        throw PersistenceException.idempotencyConflict("幂等键已被不同 worktree_apply 调用使用");
                    }
                    return json.decode(stored.response(), ManagedWorktree.class);
                }));
    }

    private void requireAuthority(ManagedWorktree worktree, TurnId parentTurnId) {
        execute(connection -> {
            requireAuthority(connection, worktree, parentTurnId);
            return null;
        });
    }

    private void requireAuthority(java.sql.Connection connection, ManagedWorktree worktree, TurnId parentTurnId)
            throws java.sql.SQLException {
        com.javaclaw.api.AgentTurn active = turns.findActiveByThread(connection, worktree.parentThreadId())
                .orElseThrow(() -> PersistenceException.invalidRequest("父 Thread 没有活动 Turn"));
        if (!active.id().equals(parentTurnId)) {
            throw PersistenceException.invalidRequest("只有绑定父 Thread 的活动 Turn 可以应用 Worktree Patch");
        }
    }

    private static String checkedDigest(String digest) {
        String normalized =
                Objects.requireNonNull(digest, "patchDigest").strip().toLowerCase(java.util.Locale.ROOT);
        if (!normalized.matches("[0-9a-f]{64}")) {
            throw PersistenceException.invalidRequest("patchDigest 必须是 SHA-256");
        }
        return normalized;
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
            throw new PersistenceException("Managed Worktree Apply 事务失败", failure);
        }
    }

    private record ApplyFingerprint(TurnId parentTurnId, WorktreeId worktreeId, String patchDigest) {}
}
