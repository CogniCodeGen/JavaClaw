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
    private final LiveTurnBudgets liveBudgets = new LiveTurnBudgets();
    private final ChildTurnService childTurns;
    private final ConversationContextService contexts;
    private final CodingEnvironmentService codingEnvironments;
    private final WorkspaceSecurityRepository workspaceSecurity;

    /**
     * 创建 Core 服务。
     *
     * @param database 已初始化或即将初始化的 data-v6 数据库
     * @param json 共享规范 JSON codec
     * @param clock 平台时钟
     */
    public CoreCommandService(H2Database database, CanonicalJson json, Clock clock) {
        transactions = new H2Transactions(Objects.requireNonNull(database, "database"));
        this.json = Objects.requireNonNull(json, "json");
        this.clock = Objects.requireNonNull(clock, "clock");
        childTurns = new ChildTurnService(database, liveBudgets, json, clock);
        contexts = new ConversationContextService(database, json);
        codingEnvironments = new CodingEnvironmentService(database, json, clock);
        workspaceSecurity = new WorkspaceSecurityRepository(database, json, clock);
    }

    /** @return 持久 Workspace 原生权限恢复锁与内部审计；不提供普通解锁入口 */
    public WorkspaceSecurityRepository workspaceSecurity() {
        return workspaceSecurity;
    }

    /** @return 项目声明准备与内部精确工具链快照服务 */
    public CodingEnvironmentService codingEnvironments() {
        return codingEnvironments;
    }

    /** @return 服务端冻结窗口与政策查询边界 */
    public ConversationContextService contexts() {
        return contexts;
    }

    /** @return 仅属于本 App Server 的活动预算注册表，供组合根注入 Harness 日志 */
    public LiveTurnBudgets liveBudgets() {
        return liveBudgets;
    }

    /** @return 与当前 Harness 共用父预算账户的子任务预留服务 */
    public ChildTurnService childTurns() {
        return childTurns;
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
        return createWorkspace(identity, name, root, Optional.empty());
    }

    /**
     * 创建 Workspace 并在同一事务保存独立执行选择。
     *
     * @param identity 幂等创建身份，expected revision 为 0
     * @param name Workspace 名称
     * @param root 绝对项目根
     * @param execution 可选执行默认值；缺省继承安装级通用 default Role
     * @return 已创建的 Workspace
     */
    public Workspace createWorkspace(
            CommandIdentity identity, String name, Path root, Optional<com.javaclaw.api.ExecutionOverrides> execution) {
        Objects.requireNonNull(identity, "identity").requireCreate();
        Objects.requireNonNull(execution, "execution");
        return idempotent(identity, Workspace.class, connection -> {
            Instant createdAt = clock.instant();
            Workspace workspace = workspaces.insert(connection, name, root, createdAt);
            instructionSettings.insert(connection, workspace.id(), createdAt);
            if (execution.isPresent()) {
                var configuration = new com.javaclaw.api.ExecutionConfiguration(
                        Optional.of(workspace.id()), Optional.empty(), execution.orElseThrow(), 1, createdAt);
                new ExecutionConfigurationRepository(json).insert(connection, configuration);
            }
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
            return instructionSettings.update(connection, current, checkedFallback, clock.instant());
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
                current -> workspaces.rename(current.connection(), current.workspace(), name, clock.instant()));
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
                identity,
                workspaceId,
                current -> workspaces.archive(current.connection(), current.workspace(), clock.instant()));
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
        Objects.requireNonNull(identity, "identity").requireCreate();
        return idempotent(identity, ConversationThread.class, connection -> {
            requireActiveWorkspace(connection, workspaceId);
            threads.validateParent(connection, workspaceId, parentId);
            return threads.insert(connection, workspaceId, parentId, executionIntent, title, clock.instant());
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
        return startTurn(identity, request, true);
    }

    /**
     * 原子创建 Turn、首条消息及通用证据资格，不允许崩溃窗口重新学习机器整理输出。
     *
     * @param identity 幂等身份
     * @param request 冻结输入
     * @param conversationEvidenceEligible 是否允许作为后续对话证据
     * @return 已创建或幂等恢复的 Turn
     */
    public AgentTurn startTurn(
            CommandIdentity identity, TurnStartRequest request, boolean conversationEvidenceEligible) {
        Objects.requireNonNull(identity, "identity").requireCreate();
        synchronized (CommandLocks.forKey(identity.idempotencyKey())) {
            Optional<AgentTurn> existing = execute(connection -> idempotency
                    .find(connection, identity.idempotencyKey())
                    .map(stored -> recover(identity, AgentTurn.class, stored)));
            if (existing.isPresent()) {
                return existing.orElseThrow();
            }
            // 原生读取不持有 H2 连接；同一幂等身份仍串行，提交时再次恢复持久响应。
            TurnStartRequest prepared = workspaceSecurity.prepare(
                    workspaceForThread(request.threadId()).id(), request, codingEnvironments);
            return idempotent(
                    identity,
                    AgentTurn.class,
                    connection -> TurnCreationWrite.insert(
                            connection, prepared, clock.instant(), json, conversationEvidenceEligible));
        }
    }

    /**
     * 恢复已提交的 turn/start 原始结果，不重新解析角色、Provider 或分配执行预算。
     *
     * @param identity 原始命令身份；请求摘要必须包含 expected revision 与 payload
     * @return 首次请求尚未提交时为空；已提交时返回创建快照，身份冲突仍按幂等契约拒绝
     */
    public Optional<AgentTurn> recoverTurnStart(CommandIdentity identity) {
        Objects.requireNonNull(identity, "identity");
        if (!identity.method().equals("turn/start")) {
            throw PersistenceException.invalidRequest("仅可恢复 turn/start 命令");
        }
        synchronized (CommandLocks.forKey(identity.idempotencyKey())) {
            Optional<AgentTurn> result = execute(connection -> idempotency
                    .find(connection, identity.idempotencyKey())
                    .map(stored -> recover(identity, AgentTurn.class, stored)));
            Objects.requireNonNull(identity, "identity").requireCreate();
            return result;
        }
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
     * 在自动化 Execution 创建前冻结完整 Prompt，不执行模型或外部工具。
     *
     * @param snapshot 分层正文与来源，按 SHA-256 去重
     */
    public void freezePromptManifest(CanonicalPayload snapshot) {
        execute(connection -> {
            new ManifestSnapshotRepository().insert(connection, snapshot);
            return null;
        });
    }

    /**
     * 读取已冻结的内容寻址 Prompt，供恢复使用；不重新访问当前角色或项目文件。
     *
     * @param digest 已保存的 SHA-256
     * @return 校验摘要后的完整快照
     */
    public CanonicalPayload promptManifest(String digest) {
        return execute(connection -> new ManifestSnapshotRepository()
                .find(connection, digest)
                .orElseThrow(() -> new PersistenceException("冻结 Prompt manifest 不存在")));
    }

    /**
     * 读取由预算预留证明的父 Turn；普通根 Thread 与自动化根执行为空。
     *
     * @param threadId 子 Thread
     * @return 父 Turn 当前状态
     */
    public Optional<AgentTurn> parentTurn(ThreadId threadId) {
        return execute(connection -> {
            Optional<TurnId> parent = new ChildTurnReservationRepository(json).parent(connection, threadId);
            return parent.isPresent() ? turns.find(connection, parent.orElseThrow()) : Optional.empty();
        });
    }

    /**
     * 读取子 Thread 在预算事务中冻结的配置，重试不得重新解析角色。
     *
     * @param threadId 已预留的子 Thread
     * @return 精确冻结配置
     */
    public com.javaclaw.api.AutomationExecutionSnapshot childSnapshot(ThreadId threadId) {
        return execute(connection -> new ChildTurnReservationRepository(json)
                .snapshot(connection, threadId)
                .orElseThrow(() -> new PersistenceException("子任务缺少父预算预留")));
    }

    /**
     * 查询持久取消意图，供子任务每次模型或工具边界传播取消。
     *
     * @param turnId Turn 身份
     * @return 是否已请求取消
     */
    public boolean cancellationRequested(TurnId turnId) {
        return execute(connection -> turns.hasCancellation(connection, turnId));
    }

    /**
     * 读取 Turn 创建时冻结的完整解析结果，恢复时不重新解析当前 Role 或默认值。
     *
     * @param turnId 已持久化 Turn
     * @return 精确配置快照；缺失表示损坏
     */
    public com.javaclaw.api.ResolvedTurnConfig resolvedConfig(TurnId turnId) {
        return execute(connection ->
                turns.configuration(connection, turnId).orElseThrow(() -> new PersistenceException("Turn 解析配置不存在")));
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
     * 读取指定 Turn 最后提交的助手消息，供子任务完成结果和审计展示使用。
     *
     * @param turnId 精确 Turn，不能读取同 Thread 的其他 Turn 内容
     * @return 最近完成的助手消息；未生成时为空
     */
    public Optional<CorePayloads.Message> turnAssistantMessage(TurnId turnId) {
        return execute(connection ->
                items
                        .listByTurnAndSchema(connection, Objects.requireNonNull(turnId, "turnId"), CoreSchemas.MESSAGE)
                        .stream()
                        .filter(item -> item.status() == ItemStatus.COMPLETED)
                        .map(item -> json.decode(item.payload(), CorePayloads.Message.class))
                        .filter(message -> message.role() == com.javaclaw.api.MessageRole.ASSISTANT)
                        .reduce((previous, current) -> current));
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
                connection -> turns.requestCancellation(
                        connection, turnId, identity.expectedRevision(), reason, clock.instant()));
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
                idempotency.insert(connection, identity, json.encode(result), clock.instant());
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
