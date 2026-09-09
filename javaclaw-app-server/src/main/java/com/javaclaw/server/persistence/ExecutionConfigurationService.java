package com.javaclaw.server.persistence;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

import com.javaclaw.api.ConversationThread;
import com.javaclaw.api.ExecutionConfiguration;
import com.javaclaw.api.ExecutionOverrides;
import com.javaclaw.api.ThreadId;
import com.javaclaw.api.WorkspaceId;
import com.javaclaw.protocol.CanonicalJson;

/** 版本化执行默认值和 Thread 覆盖；只负责持久化，所有层次合并由唯一配置解析器完成。 */
public final class ExecutionConfigurationService {
    private final H2Transactions transactions;
    private final ExecutionConfigurationRepository configurations;
    private final ExecutionConfigurationRepository subagentConfigurations;
    private final ExecutionConfigurationRepository recentSelections;
    private final IdempotencyRepository idempotency = new IdempotencyRepository();
    private final CoreCommandService core;
    private final AgentRoleService roles;
    private final CanonicalJson json;
    private final Clock clock;

    /**
     * 创建执行配置服务。
     *
     * @param database data-v6 数据库
     * @param core Workspace 与 Thread 查询服务
     * @param roles 精确角色服务
     * @param json 规范 JSON codec
     * @param clock 平台时钟
     */
    public ExecutionConfigurationService(
            H2Database database, CoreCommandService core, AgentRoleService roles, CanonicalJson json, Clock clock) {
        transactions = new H2Transactions(Objects.requireNonNull(database, "database"));
        this.core = Objects.requireNonNull(core, "core");
        this.roles = Objects.requireNonNull(roles, "roles");
        this.json = Objects.requireNonNull(json, "json");
        this.clock = Objects.requireNonNull(clock, "clock");
        configurations = new ExecutionConfigurationRepository(json);
        subagentConfigurations =
                new ExecutionConfigurationRepository(json, ExecutionConfigurationRepository.Namespace.SUBAGENT);
        recentSelections =
                new ExecutionConfigurationRepository(json, ExecutionConfigurationRepository.Namespace.RECENT);
    }

    /**
     * 读取作用域直接配置，不向父级回退。
     *
     * @param workspaceId Workspace；安装级为空
     * @param threadId Thread；安装和 Workspace 级为空
     * @return 已存配置，不存在时为空
     */
    public Optional<ExecutionConfiguration> find(Optional<WorkspaceId> workspaceId, Optional<ThreadId> threadId) {
        validateScope(workspaceId, threadId);
        return execute(connection -> configurations.find(connection, workspaceId, threadId, false));
    }

    /**
     * 读取仅用于初始化新 Thread 的最近模型与思考选择，不回退安装默认。
     *
     * <p>该记录使用独立命名空间，永远不参与已有 Thread 的执行配置继承链。
     *
     * @return 最近选择的独立版本；缺失时新 Thread 应按正常规则继承项目设置
     */
    public Optional<ExecutionConfiguration> findRecent() {
        return execute(connection -> recentSelections.find(connection, Optional.empty(), Optional.empty(), false));
    }

    /**
     * 保存最近模型与思考，不修改安装默认、项目默认或任何已有 Thread。
     *
     * @param identity 最近选择自身的版本和幂等身份
     * @param overrides 只允许 Provider 和 reasoning；空字段表示新 Thread 继承正常设置
     * @return 最近选择的已提交版本，作用域字段均为空
     */
    public ExecutionConfiguration updateRecent(CommandIdentity identity, ExecutionOverrides overrides) {
        requireModelFields(overrides, "最近选择只能设置 Provider 和推理偏好");
        return update(identity, Optional.empty(), Optional.empty(), overrides, recentSelections);
    }

    /**
     * 在一个只读事务内检查归属并读取安装、Workspace、Thread 配置链。
     *
     * @param workspaceId 执行 Workspace
     * @param threadId 可选 Thread；存在时必须属于 Workspace
     * @return 按覆盖顺序排列的已有配置，缺失作用域不生成伪版本
     */
    public java.util.List<ExecutionConfiguration> findForTurn(WorkspaceId workspaceId, Optional<ThreadId> threadId) {
        return findChain(workspaceId, threadId, configurations);
    }

    /**
     * 以相同事务边界读取子任务模型默认值，不与普通 Turn 默认值混合。
     *
     * @param workspaceId 父 Turn Workspace
     * @param threadId 可选父 Thread
     * @return 安装、Workspace、Thread 的独立子任务配置链
     */
    public java.util.List<ExecutionConfiguration> findSubagentDefaultsForTurn(
            WorkspaceId workspaceId, Optional<ThreadId> threadId) {
        return findChain(workspaceId, threadId, subagentConfigurations);
    }

    private java.util.List<ExecutionConfiguration> findChain(
            WorkspaceId workspaceId, Optional<ThreadId> threadId, ExecutionConfigurationRepository repository) {
        Objects.requireNonNull(workspaceId, "workspaceId");
        Objects.requireNonNull(threadId, "threadId");
        return execute(connection -> {
            new WorkspaceRepository()
                    .find(connection, workspaceId)
                    .orElseThrow(() -> PersistenceException.invalidRequest("Workspace 不存在"));
            if (threadId.isPresent()) {
                var thread = new ThreadRepository()
                        .find(connection, threadId.orElseThrow())
                        .orElseThrow(() -> PersistenceException.invalidRequest("Thread 不存在"));
                if (!thread.workspaceId().equals(workspaceId)) {
                    throw PersistenceException.invalidRequest("Thread 不属于 Workspace");
                }
            }
            java.util.List<ExecutionConfiguration> chain = new java.util.ArrayList<>();
            repository
                    .find(connection, Optional.empty(), Optional.empty(), false)
                    .ifPresent(chain::add);
            repository
                    .find(connection, Optional.of(workspaceId), Optional.empty(), false)
                    .ifPresent(chain::add);
            if (threadId.isPresent()) {
                repository
                        .find(connection, Optional.of(workspaceId), threadId, false)
                        .ifPresent(chain::add);
            }
            return java.util.List.copyOf(chain);
        });
    }

    /**
     * 原子创建或更新配置，并记录幂等结果；更新仅影响新 Turn。
     *
     * @param identity expected revision 是当前配置版本，新建为 0
     * @param workspaceId Workspace；安装级为空
     * @param threadId Thread；安装和 Workspace 级为空
     * @param overrides 显式选择；缺省字段由解析器继承
     * @return 已提交配置
     */
    public ExecutionConfiguration update(
            CommandIdentity identity,
            Optional<WorkspaceId> workspaceId,
            Optional<ThreadId> threadId,
            ExecutionOverrides overrides) {
        return update(identity, workspaceId, threadId, overrides, configurations);
    }

    /**
     * 读取子智能体模型与推理默认值，缺失时由解析器继承父模型。
     *
     * @param workspaceId Workspace；安装级为空
     * @param threadId Thread；安装和 Workspace 级为空
     * @return 该作用域的独立子任务默认值
     */
    public Optional<ExecutionConfiguration> findSubagentDefaults(
            Optional<WorkspaceId> workspaceId, Optional<ThreadId> threadId) {
        validateScope(workspaceId, threadId);
        return execute(connection -> subagentConfigurations.find(connection, workspaceId, threadId, false));
    }

    /**
     * 保存仅包含 Provider 和推理的子任务默认值；不能修改父权限、预算、角色或审批。
     *
     * @param identity 当前子默认配置的版本与幂等身份
     * @param workspaceId Workspace；安装级为空
     * @param threadId Thread；安装和 Workspace 级为空
     * @param overrides 只允许模型和推理两个可选字段
     * @return 已提交版本，影响之后创建的子 Turn
     */
    public ExecutionConfiguration updateSubagentDefaults(
            CommandIdentity identity,
            Optional<WorkspaceId> workspaceId,
            Optional<ThreadId> threadId,
            ExecutionOverrides overrides) {
        requireModelFields(overrides, "子任务默认值只能设置 Provider 和推理偏好");
        return update(identity, workspaceId, threadId, overrides, subagentConfigurations);
    }

    private static void requireModelFields(ExecutionOverrides overrides, String message) {
        Objects.requireNonNull(overrides, "overrides");
        if (overrides.role().isPresent()
                || overrides.permissionProfile().isPresent()
                || overrides.approvalPolicy().isPresent()
                || overrides.budget().isPresent()
                || overrides.visibleCapabilities().isPresent()) {
            throw PersistenceException.invalidRequest(message);
        }
    }

    private ExecutionConfiguration update(
            CommandIdentity identity,
            Optional<WorkspaceId> workspaceId,
            Optional<ThreadId> threadId,
            ExecutionOverrides overrides,
            ExecutionConfigurationRepository repository) {
        validateScope(workspaceId, threadId);
        Objects.requireNonNull(overrides, "overrides");
        synchronized (CommandLocks.forKey(identity.idempotencyKey())) {
            return execute(connection -> {
                Optional<IdempotencyRepository.StoredCommand> stored =
                        idempotency.find(connection, identity.idempotencyKey());
                if (stored.isPresent()) {
                    return recover(identity, stored.orElseThrow());
                }
                overrides.role().ifPresent(role -> roles.requireAvailable(role.id(), role.revision()));
                Optional<ExecutionConfiguration> current = repository.find(connection, workspaceId, threadId, true);
                requireRevision(current, identity.expectedRevision());
                ExecutionConfiguration next = new ExecutionConfiguration(
                        workspaceId,
                        threadId,
                        overrides,
                        Math.addExact(identity.expectedRevision(), 1),
                        Instant.now(clock));
                if (current.isEmpty()) {
                    repository.insert(connection, next);
                } else {
                    repository.update(connection, next, identity.expectedRevision());
                }
                idempotency.insert(connection, identity, json.encode(next), next.updatedAt());
                return next;
            });
        }
    }

    /**
     * 仅当安装配置尚不存在时写入发行默认值，重启时保留已确认的用户选择。
     *
     * @param defaults 安装级默认选择，允许 Provider 尚未配置
     * @return 当前安装配置
     */
    public ExecutionConfiguration ensureInstallationDefaults(ExecutionOverrides defaults) {
        Objects.requireNonNull(defaults, "defaults");
        synchronized (CommandLocks.forKey("execution-installation-initialize")) {
            return execute(connection -> {
                Optional<ExecutionConfiguration> existing =
                        configurations.find(connection, Optional.empty(), Optional.empty(), true);
                if (existing.isPresent()) {
                    return existing.orElseThrow();
                }
                ExecutionConfiguration initial =
                        new ExecutionConfiguration(Optional.empty(), Optional.empty(), defaults, 1, Instant.now(clock));
                configurations.insert(connection, initial);
                return initial;
            });
        }
    }

    private void validateScope(Optional<WorkspaceId> workspaceId, Optional<ThreadId> threadId) {
        Objects.requireNonNull(workspaceId, "workspaceId");
        Objects.requireNonNull(threadId, "threadId");
        if (threadId.isPresent() && workspaceId.isEmpty()) {
            throw PersistenceException.invalidRequest("Thread 配置必须指定 Workspace");
        }
        workspaceId.ifPresent(
                id -> core.findWorkspace(id).orElseThrow(() -> PersistenceException.invalidRequest("Workspace 不存在")));
        threadId.ifPresent(id -> {
            ConversationThread thread =
                    core.findThread(id).orElseThrow(() -> PersistenceException.invalidRequest("Thread 不存在"));
            if (!thread.workspaceId().equals(workspaceId.orElseThrow())) {
                throw PersistenceException.invalidRequest("Thread 不属于 Workspace");
            }
        });
    }

    private ExecutionConfiguration recover(CommandIdentity identity, IdempotencyRepository.StoredCommand stored) {
        if (!stored.method().equals(identity.method())
                || !stored.requestDigest().equals(identity.requestDigest())) {
            throw PersistenceException.idempotencyConflict("幂等键已被不同命令使用");
        }
        return json.decode(stored.response(), ExecutionConfiguration.class);
    }

    private static void requireRevision(Optional<ExecutionConfiguration> current, long expectedRevision) {
        if (current.isEmpty() && expectedRevision == 0) {
            return;
        }
        if (current.isEmpty() || current.orElseThrow().revision() != expectedRevision) {
            throw PersistenceException.revisionConflict("执行配置 revision 已改变");
        }
    }

    private <T> T execute(H2Transactions.SqlWork<T> work) {
        try {
            return transactions.execute(work);
        } catch (RuntimeException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new PersistenceException("执行配置事务失败", failure);
        }
    }
}
