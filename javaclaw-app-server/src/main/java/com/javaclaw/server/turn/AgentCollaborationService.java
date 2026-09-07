package com.javaclaw.server.turn;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import com.javaclaw.api.AgentRole;
import com.javaclaw.api.AgentRoleRef;
import com.javaclaw.api.AgentTurn;
import com.javaclaw.api.AutomationExecutionSnapshot;
import com.javaclaw.api.ConversationThread;
import com.javaclaw.api.CorePayloads;
import com.javaclaw.api.ExecutionOverrides;
import com.javaclaw.api.MessageRole;
import com.javaclaw.api.PermissionConstraint;
import com.javaclaw.api.ThreadExecutionIntent;
import com.javaclaw.api.TurnBudget;
import com.javaclaw.api.TurnId;
import com.javaclaw.protocol.CanonicalJson;
import com.javaclaw.protocol.CollaborationRpcContracts;
import com.javaclaw.protocol.CoreRpcContracts;
import com.javaclaw.server.persistence.ChildThreadReservation;
import com.javaclaw.server.persistence.ChildTurnService;
import com.javaclaw.server.persistence.CommandIdentity;
import com.javaclaw.server.persistence.PersistenceException;

/** 子智能体应用用例；角色解析、预算预留、Worktree 与模型执行沿用现有平台服务。 */
public final class AgentCollaborationService {
    private final TurnPlatformServices services;
    private final ChildTurnService children;
    private final HarnessTurnDispatcher dispatcher;
    private final CanonicalJson json;
    private final Clock clock;

    /**
     * 创建协作用例。
     *
     * @param services 平台权威服务
     * @param children 子 Thread 与预算事务
     * @param dispatcher 唯一 Harness 调度器
     * @param json 规范 JSON
     * @param clock 平台时钟
     */
    public AgentCollaborationService(
            TurnPlatformServices services,
            ChildTurnService children,
            HarnessTurnDispatcher dispatcher,
            CanonicalJson json,
            Clock clock) {
        this.services = Objects.requireNonNull(services, "services");
        this.children = Objects.requireNonNull(children, "children");
        this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher");
        this.json = Objects.requireNonNull(json, "json");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * 创建或恢复子任务并异步调度；调用方仅选择 agentType，不构造持久配置组合。
     *
     * @param identity 幂等键及父 Turn expected revision
     * @param request 有限子任务输入和独立选择
     * @return 已持久化子任务及脱敏解析摘要
     */
    public CollaborationRpcContracts.SpawnResult spawn(
            CommandIdentity identity, CollaborationRpcContracts.SpawnPayload request) {
        CommandIdentity threadIdentity = derived(identity, "thread", identity.expectedRevision(), request);
        ConversationThread thread = children.recover(threadIdentity).orElseGet(() -> reserve(threadIdentity, request));
        AutomationExecutionSnapshot snapshot = services.core().childSnapshot(thread.id());
        if (thread.executionIntent() == ThreadExecutionIntent.ISOLATED_WRITE) {
            services.worktrees()
                    .provisionForChild(
                            derived(identity, "worktree", "worktree/provision", 0, request),
                            thread.workspaceId(),
                            thread.parentThreadId().orElseThrow(),
                            thread.id());
        }
        CoreRpcContracts.TurnStartPayload payload = new CoreRpcContracts.TurnStartPayload(
                thread.id(), AgentConfigurationResolver.overrides(snapshot.configuration()), request.message());
        CorePayloads.Message input =
                new CorePayloads.Message(MessageRole.USER, request.message(), List.of(), Optional.empty());
        AgentTurn turn = children.started(thread.id()).orElseGet(() -> {
            var prepared = dispatcher.resolveOrchestrated(payload, input, snapshot);
            return services.core().startTurn(derived(identity, "turn", 0, request), prepared);
        });
        dispatcher.dispatchChild(turn, payload, snapshot);
        return new CollaborationRpcContracts.SpawnResult(thread, turn, turn.resolvedConfig());
    }

    /**
     * 查询子任务当前状态，不调用模型或改变状态。
     *
     * @param turnId 子 Turn
     * @return 权威 Turn 快照
     */
    public AgentTurn read(TurnId turnId) {
        AgentTurn turn =
                services.core().findTurn(turnId).orElseThrow(() -> PersistenceException.invalidRequest("子 Turn 不存在"));
        if (services.core().parentTurn(turn.threadId()).isEmpty()) {
            throw PersistenceException.invalidRequest("指定 Turn 不是预算预留创建的子任务");
        }
        return turn;
    }

    /**
     * 持久取消子任务并向执行栈传播。
     *
     * @param identity 子 Turn expected revision 与幂等身份
     * @param turnId 子 Turn
     * @param reason 取消原因
     * @return 已保存取消意图的快照
     */
    public AgentTurn interrupt(CommandIdentity identity, TurnId turnId, String reason) {
        read(turnId);
        AgentTurn turn = services.core().requestTurnCancellation(identity, turnId, reason);
        dispatcher.cancel(turnId, reason);
        return turn;
    }

    private ConversationThread reserve(CommandIdentity identity, CollaborationRpcContracts.SpawnPayload request) {
        AgentTurn parent = services.core()
                .findTurn(request.parentTurnId())
                .orElseThrow(() -> PersistenceException.invalidRequest("父 Turn 不存在"));
        requireSpawnAllowed(parent);
        AgentRole role = services.roles().listLatest().stream()
                .filter(value -> value.id().equals(request.agentType()))
                .findFirst()
                .orElseThrow(() -> PersistenceException.invalidRequest("agentType 不存在"));
        ExecutionOverrides selection = childSelection(parent, role, request.execution());
        AutomationExecutionSnapshot snapshot = dispatcher.freezeChild(parent, selection);
        ThreadExecutionIntent intent = snapshot.configuration().permissionConstraint() == PermissionConstraint.READ_ONLY
                ? ThreadExecutionIntent.READ_ONLY
                : ThreadExecutionIntent.ISOLATED_WRITE;
        return children.reserve(
                identity,
                new ChildThreadReservation(
                        parent.id(), snapshot.configuration(), snapshot.toolCatalog(), intent, request.title()));
    }

    private void requireSpawnAllowed(AgentTurn parent) {
        var frozen = services.core().resolvedConfig(parent.id());
        var scope = ThreadExecutionScope.resolve(services.core(), services.worktrees(), parent.threadId());
        var resolver = new AgentConfigurationResolver(
                services.roles(), services.configurations(), services.permissions(), services.core());
        var current =
                ParentTurnPermissions.intersect(services, json, parent, resolver.restorePermissions(frozen, scope));
        var catalog = json.decode(
                services.core().toolCatalogSnapshot(parent.id()), com.javaclaw.api.ToolCatalogSnapshot.class);
        boolean enabled =
                catalog.tools().stream().anyMatch(tool -> tool.identity().name().equals(CollaborationTools.SPAWN));
        if (!enabled
                || !current.tools().allowedTools().contains(CollaborationTools.SPAWN)
                || current.tools().maximumRisk().ordinal() < com.javaclaw.api.ToolRisk.EXTERNAL_EFFECT.ordinal()) {
            throw PersistenceException.invalidRequest("父 Turn 的冻结目录或实时权限未允许创建子智能体");
        }
    }

    private ExecutionOverrides childSelection(AgentTurn parent, AgentRole role, ExecutionOverrides requested) {
        AgentRoleRef reference = new AgentRoleRef(role.id(), role.revision());
        if (requested.role().filter(value -> !value.equals(reference)).isPresent()) {
            throw PersistenceException.invalidRequest("agentType 与显式 Role 不能冲突");
        }
        Duration remaining = Duration.between(
                        clock.instant(), parent.createdAt().plus(parent.budget().wallTime()))
                .minusMillis(1_000);
        if (remaining.isNegative() || remaining.isZero()) {
            throw PersistenceException.invalidRequest("父 Turn 没有剩余时间预算");
        }
        TurnBudget budget = requested
                .budget()
                .orElseGet(() -> new TurnBudget(
                        Math.max(1, parent.budget().inputTokens() / 4),
                        Math.max(1, parent.budget().outputTokens() / 4),
                        Math.max(1, parent.budget().toolCalls() / 4),
                        parent.budget().childThreads(),
                        remaining.compareTo(Duration.ofMinutes(5)) < 0 ? remaining : Duration.ofMinutes(5)));
        TurnBudget bounded = new TurnBudget(
                budget.inputTokens(),
                budget.outputTokens(),
                budget.toolCalls(),
                budget.childThreads(),
                budget.wallTime().compareTo(remaining) < 0 ? budget.wallTime() : remaining);
        return new ExecutionOverrides(
                Optional.of(reference),
                requested.provider(),
                requested.permissionProfile(),
                requested.approvalPolicy(),
                Optional.of(bounded),
                requested.visibleCapabilities(),
                requested.reasoning());
    }

    private CommandIdentity derived(CommandIdentity identity, String operation, long revision, Object request) {
        return derived(identity, operation, "agent/spawn/" + operation, revision, request);
    }

    private CommandIdentity derived(
            CommandIdentity identity, String operation, String method, long revision, Object request) {
        return new CommandIdentity(
                method,
                identity.idempotencyKey() + ":" + operation,
                revision,
                json.encode(request).sha256());
    }
}
