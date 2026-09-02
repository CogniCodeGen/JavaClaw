package com.javaclaw.server.persistence;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import com.javaclaw.api.AgentTurn;
import com.javaclaw.api.CanonicalPayload;
import com.javaclaw.api.ConversationThread;
import com.javaclaw.api.CorePayloads;
import com.javaclaw.api.CoreSchemas;
import com.javaclaw.api.ItemEnvelope;
import com.javaclaw.api.ItemStatus;
import com.javaclaw.api.ThreadExecutionIntent;
import com.javaclaw.api.ThreadId;
import com.javaclaw.api.TurnId;
import com.javaclaw.api.UnattendedExecutionScope;
import com.javaclaw.api.Workspace;
import com.javaclaw.api.WorkspaceId;
import com.javaclaw.api.WorkspaceInstructionSettings;
import com.javaclaw.api.WorkspaceLifecycle;
import com.javaclaw.protocol.CanonicalJson;

/** Workspace、Thread 与 Turn 的精简命令/查询服务。 */
public final class CoreCommandService {
    private final H2Transactions transactions;
    private final WorkspaceRepository workspaces = new WorkspaceRepository();
    private final WorkspaceInstructionSettingsRepository instructionSettings =
            new WorkspaceInstructionSettingsRepository();
    private final ThreadRepository threads = new ThreadRepository();
    private final TurnRepository turns = new TurnRepository();
    private final TurnPromptRepository prompts = new TurnPromptRepository();
    private final TurnToolCatalogRepository toolCatalogs = new TurnToolCatalogRepository();
    private final UnattendedTurnScopeRepository unattendedScopes = new UnattendedTurnScopeRepository();
    private final ItemRepository items = new ItemRepository(turns);
    private final IdempotencyRepository idempotency = new IdempotencyRepository();
    private final CanonicalJson json;
    private final Clock clock;

    /**
     * 创建 Core 服务。
     *
     * @param database 已初始化或即将初始化的 data-v5 数据库
     * @param json 共享规范 JSON codec
     * @param clock 平台时钟
     */
    public CoreCommandService(H2Database database, CanonicalJson json, Clock clock) {
        transactions = new H2Transactions(Objects.requireNonNull(database, "database"));
        this.json = Objects.requireNonNull(json, "json");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * 创建 Workspace。
     *
     * @param identity 幂等命令身份，expected revision 必须为 0
     * @param name 名称
     * @param root 绝对根目录
     * @return 新快照
     */
    public Workspace createWorkspace(CommandIdentity identity, String name, Path root) {
        requireCreate(identity);
        return idempotent(identity, Workspace.class, connection -> {
            Instant createdAt = now();
            Workspace workspace = workspaces.insert(connection, name, root, createdAt);
            instructionSettings.insert(connection, workspace.id(), createdAt);
            return workspace;
        });
    }

    /**
     * 读取 Workspace 项目约定设置。
     *
     * @param workspaceId Workspace
     * @return 权威设置快照
     */
    public WorkspaceInstructionSettings workspaceInstructionSettings(WorkspaceId workspaceId) {
        return execute(connection -> instructionSettings
                .find(connection, Objects.requireNonNull(workspaceId, "workspaceId"), false)
                .orElseThrow(() -> PersistenceException.invalidRequest("Workspace 或项目约定设置不存在")));
    }

    /**
     * 更新 Workspace 的安全 fallback 文件名。
     *
     * @param identity 幂等命令身份；expected revision 属于项目约定设置
     * @param workspaceId Workspace
     * @param fallbackBasename 安全 basename；空值表示只使用标准 AGENTS 文件名
     * @return 新版本设置
     */
    public WorkspaceInstructionSettings updateWorkspaceInstructionSettings(
            CommandIdentity identity, WorkspaceId workspaceId, Optional<String> fallbackBasename) {
        CommandIdentity checked = Objects.requireNonNull(identity, "identity");
        if (checked.expectedRevision() < 1) {
            throw PersistenceException.invalidRequest("项目约定设置更新必须提供正数 expected revision");
        }
        WorkspaceId checkedWorkspace = Objects.requireNonNull(workspaceId, "workspaceId");
        Optional<String> checkedFallback = Objects.requireNonNull(fallbackBasename, "fallbackBasename");
        return idempotent(checked, WorkspaceInstructionSettings.class, connection -> {
            WorkspaceInstructionSettings current = instructionSettings
                    .find(connection, checkedWorkspace, true)
                    .orElseThrow(() -> PersistenceException.invalidRequest("Workspace 或项目约定设置不存在"));
            if (current.revision() != checked.expectedRevision()) {
                throw PersistenceException.revisionConflict("项目约定设置 revision 已改变");
            }
            return instructionSettings.update(connection, current, checkedFallback, now());
        });
    }

    /**
     * 查询 Workspace。
     *
     * @param id 标识
     * @return 当前快照
     */
    public Optional<Workspace> findWorkspace(WorkspaceId id) {
        return execute(connection -> workspaces.find(connection, id));
    }

    /**
     * 列出全部 Workspace。
     *
     * @return 稳定排序的不可变列表
     */
    public List<Workspace> listWorkspaces() {
        return execute(workspaces::list);
    }

    /**
     * 重命名 Workspace；根目录始终不变。
     *
     * @param identity 幂等命令身份；expected revision 必须匹配当前版本
     * @param workspaceId Workspace
     * @param name 新的用户可见名称
     * @return 新版本快照
     */
    public Workspace renameWorkspace(CommandIdentity identity, WorkspaceId workspaceId, String name) {
        return updateWorkspace(
                identity,
                workspaceId,
                current -> workspaces.rename(current.connection(), current.workspace(), name, now()));
    }

    /**
     * 归档 Workspace 登记；不删除或修改用户目录。
     *
     * @param identity 幂等命令身份；expected revision 必须匹配当前版本
     * @param workspaceId Workspace
     * @return 归档版本快照
     */
    public Workspace archiveWorkspace(CommandIdentity identity, WorkspaceId workspaceId) {
        return updateWorkspace(
                identity, workspaceId, current -> workspaces.archive(current.connection(), current.workspace(), now()));
    }

    /**
     * 创建 Thread。
     *
     * @param identity 幂等命令身份，expected revision 必须为 0
     * @param workspaceId 所属 Workspace
     * @param parentId 父 Thread；根 Thread 为空
     * @param executionIntent 服务端验证的执行隔离意图
     * @param title 标题
     * @return 新快照
     */
    public ConversationThread createThread(
            CommandIdentity identity,
            WorkspaceId workspaceId,
            Optional<ThreadId> parentId,
            ThreadExecutionIntent executionIntent,
            String title) {
        requireCreate(identity);
        return idempotent(identity, ConversationThread.class, connection -> {
            requireActiveWorkspace(connection, workspaceId);
            validateParent(connection, workspaceId, parentId);
            return threads.insert(connection, workspaceId, parentId, executionIntent, title, now());
        });
    }

    /**
     * 查询 Thread。
     *
     * @param id 标识
     * @return 当前快照
     */
    public Optional<ConversationThread> findThread(ThreadId id) {
        return execute(connection -> threads.find(connection, id));
    }

    /**
     * 列出 Workspace 中的 Thread。
     *
     * @param workspaceId Workspace
     * @return 最近更新优先的列表
     */
    public List<ConversationThread> listThreads(WorkspaceId workspaceId) {
        return execute(connection -> threads.listByWorkspace(connection, workspaceId));
    }

    /**
     * 读取 Thread 所属 Workspace。
     *
     * @param threadId Thread
     * @return Workspace 快照
     */
    public Workspace workspaceForThread(ThreadId threadId) {
        return execute(connection -> {
            ConversationThread thread = threads.find(connection, threadId)
                    .orElseThrow(() -> PersistenceException.invalidRequest("Thread 不存在"));
            return workspaces
                    .find(connection, thread.workspaceId())
                    .orElseThrow(() -> PersistenceException.invalidRequest("Workspace 不存在"));
        });
    }

    /**
     * 同事务创建排队中的 Turn 和首条用户消息；同一 Thread 只允许一个活动 Turn。
     *
     * @param identity 幂等命令身份，expected revision 必须为 0
     * @param request Turn 与消息参数
     * @return 新快照
     */
    public AgentTurn startTurn(CommandIdentity identity, TurnStartRequest request) {
        requireCreate(identity);
        return idempotent(identity, AgentTurn.class, connection -> {
            turns.lockThread(connection, request.threadId());
            if (turns.hasActiveTurn(connection, request.threadId())) {
                throw PersistenceException.revisionConflict("Thread 已有活动 Turn");
            }
            Instant createdAt = now();
            AgentTurn turn = turns.insert(connection, request, createdAt);
            prompts.insert(connection, turn.id(), request.promptSnapshot());
            toolCatalogs.insert(connection, turn.id(), request.toolCatalog(), json);
            if (request.unattendedExecutionScope().isPresent()) {
                unattendedScopes.insert(
                        connection,
                        turn.id(),
                        request.unattendedExecutionScope().orElseThrow(),
                        createdAt);
            }
            ItemRepository.ItemWrite item = new ItemRepository.ItemWrite(
                    turn.id(),
                    "message",
                    CoreSchemas.MESSAGE,
                    "core",
                    ItemStatus.COMPLETED,
                    json.encode(request.message()),
                    createdAt);
            items.append(connection, item);
            return turn;
        });
    }

    /**
     * 查询 Turn。
     *
     * @param id 标识
     * @return 当前快照
     */
    public Optional<AgentTurn> findTurn(TurnId id) {
        return execute(connection -> turns.find(connection, id));
    }

    /**
     * 读取 Turn 创建事务中冻结的 Schedule 无人值守来源。
     *
     * @param turnId Turn
     * @return 仅 Schedule Occurrence 创建的 Turn 才存在
     */
    public Optional<UnattendedExecutionScope> unattendedExecutionScope(TurnId turnId) {
        TurnId checked = Objects.requireNonNull(turnId, "turnId");
        return execute(connection -> unattendedScopes.find(connection, checked));
    }

    /**
     * 列出启动时必须重建执行栈的 Turn。
     *
     * @return 按创建时间稳定排序的 QUEUED/RUNNING Turn
     */
    public List<AgentTurn> listRecoverableTurns() {
        return execute(turns::listRecoverable);
    }

    /**
     * 读取 Turn 创建事务中提交的原始用户消息。
     *
     * @param turnId Turn
     * @return 冻结用户消息
     */
    public CorePayloads.Message turnUserMessage(TurnId turnId) {
        TurnId checked = Objects.requireNonNull(turnId, "turnId");
        return execute(connection -> items.listByTurnAndSchema(connection, checked, CoreSchemas.MESSAGE).stream()
                .map(item -> json.decode(item.payload(), CorePayloads.Message.class))
                .filter(message -> message.role() == com.javaclaw.api.MessageRole.USER)
                .findFirst()
                .orElseThrow(() -> new PersistenceException("Turn 缺少创建时用户消息")));
    }

    /**
     * 读取 Turn 创建时冻结的 Prompt snapshot。
     *
     * @param id Turn 标识
     * @return 规范 payload；不存在表示数据损坏
     */
    public CanonicalPayload promptSnapshot(TurnId id) {
        return execute(connection -> prompts.find(connection, Objects.requireNonNull(id, "turnId"))
                .orElseThrow(() -> new PersistenceException("Turn Prompt snapshot 不存在")));
    }

    /**
     * 读取 Turn 创建时冻结的完整工具目录。
     *
     * @param id Turn 标识
     * @return 规范 payload；不存在表示数据损坏
     */
    public CanonicalPayload toolCatalogSnapshot(TurnId id) {
        return execute(connection -> toolCatalogs
                .find(connection, Objects.requireNonNull(id, "turnId"), json)
                .orElseThrow(() -> new PersistenceException("Turn 工具目录 snapshot 不存在")));
    }

    /**
     * 持久化取消请求并推进 Turn revision。
     *
     * @param identity 幂等命令身份；expected revision 必须匹配 Turn
     * @param turnId Turn
     * @param reason 脱敏原因
     * @return 已记录取消请求的 Turn 快照
     */
    public AgentTurn requestTurnCancellation(CommandIdentity identity, TurnId turnId, String reason) {
        if (Objects.requireNonNull(identity, "identity").expectedRevision() < 1) {
            throw PersistenceException.invalidRequest("取消命令必须提供正数 expected revision");
        }
        return idempotent(
                identity,
                AgentTurn.class,
                connection ->
                        turns.requestCancellation(connection, turnId, identity.expectedRevision(), reason, now()));
    }

    /**
     * 按稳定 sequence 读取 Thread 的 Item。
     *
     * @param threadId Thread
     * @return 不可变 Item 列表
     */
    public List<ItemEnvelope> listItems(ThreadId threadId) {
        return execute(connection -> items.listByThread(connection, threadId));
    }

    /**
     * 分页读取 Thread Item。
     *
     * @param threadId Thread
     * @param afterSequence 游标
     * @param limit 页大小
     * @return Item 页
     */
    public List<ItemEnvelope> listItems(ThreadId threadId, long afterSequence, int limit) {
        if (afterSequence < 0 || limit < 1 || limit > 1_000) {
            throw new IllegalArgumentException("invalid item page");
        }
        return execute(connection -> items.listPage(connection, threadId, afterSequence, limit));
    }

    private void validateParent(java.sql.Connection connection, WorkspaceId workspaceId, Optional<ThreadId> parentId)
            throws java.sql.SQLException {
        if (parentId.isEmpty()) {
            return;
        }
        ConversationThread parent = threads.find(connection, parentId.orElseThrow())
                .orElseThrow(() -> new PersistenceException("父 Thread 不存在"));
        if (!parent.workspaceId().equals(workspaceId)) {
            throw new PersistenceException("父子 Thread 必须属于同一 Workspace");
        }
    }

    private Workspace updateWorkspace(CommandIdentity identity, WorkspaceId workspaceId, WorkspaceUpdate update) {
        CommandIdentity checked = Objects.requireNonNull(identity, "identity");
        if (checked.expectedRevision() < 1) {
            throw PersistenceException.invalidRequest("Workspace 更新必须提供正数 expected revision");
        }
        return idempotent(checked, Workspace.class, connection -> {
            Workspace current = workspaces
                    .lock(connection, Objects.requireNonNull(workspaceId, "workspaceId"))
                    .orElseThrow(() -> PersistenceException.invalidRequest("Workspace 不存在"));
            if (current.revision() != checked.expectedRevision()) {
                throw PersistenceException.revisionConflict("Workspace revision 已改变");
            }
            return update.apply(new LockedWorkspace(connection, current));
        });
    }

    private void requireActiveWorkspace(java.sql.Connection connection, WorkspaceId workspaceId)
            throws java.sql.SQLException {
        Workspace workspace = workspaces
                .find(connection, Objects.requireNonNull(workspaceId, "workspaceId"))
                .orElseThrow(() -> PersistenceException.invalidRequest("Workspace 不存在"));
        if (workspace.lifecycle() != WorkspaceLifecycle.ACTIVE) {
            throw PersistenceException.invalidRequest("已归档 Workspace 不能创建新 Thread");
        }
    }

    private <T> T idempotent(CommandIdentity identity, Class<T> resultType, H2Transactions.SqlWork<T> work) {
        Objects.requireNonNull(identity, "identity");
        synchronized (CommandLocks.forKey(identity.idempotencyKey())) {
            return execute(connection -> {
                Optional<IdempotencyRepository.StoredCommand> stored =
                        idempotency.find(connection, identity.idempotencyKey());
                if (stored.isPresent()) {
                    return recover(identity, resultType, stored.orElseThrow());
                }
                T result = work.execute(connection);
                idempotency.insert(connection, identity, json.encode(result), now());
                return result;
            });
        }
    }

    private <T> T recover(CommandIdentity identity, Class<T> resultType, IdempotencyRepository.StoredCommand stored) {
        if (!stored.method().equals(identity.method())
                || !stored.requestDigest().equals(identity.requestDigest())) {
            throw PersistenceException.idempotencyConflict("幂等键已被不同命令使用");
        }
        return json.decode(stored.response(), resultType);
    }

    private static void requireCreate(CommandIdentity identity) {
        if (Objects.requireNonNull(identity, "identity").expectedRevision() != 0) {
            throw PersistenceException.invalidRequest("创建命令 expected revision 必须为 0");
        }
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
            throw new PersistenceException("Core 事务失败", failure);
        }
    }

    @FunctionalInterface
    private interface WorkspaceUpdate {
        Workspace apply(LockedWorkspace current) throws Exception;
    }

    private record LockedWorkspace(java.sql.Connection connection, Workspace workspace) {}
}
