package com.javaclaw.server.turn;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.javaclaw.api.AgentTurn;
import com.javaclaw.api.AutomationExecutionSnapshot;
import com.javaclaw.api.CancellationSource;
import com.javaclaw.api.CancellationToken;
import com.javaclaw.api.CorePayloads;
import com.javaclaw.api.CoreSchemas;
import com.javaclaw.api.ItemStatus;
import com.javaclaw.api.TurnId;
import com.javaclaw.api.TurnStatus;
import com.javaclaw.api.WorkspaceId;
import com.javaclaw.protocol.CanonicalJson;
import com.javaclaw.protocol.CoreRpcContracts;
import com.javaclaw.runtime.ModelGateway;
import com.javaclaw.runtime.ModelUsage;
import com.javaclaw.runtime.TurnExecutionCommand;
import com.javaclaw.runtime.TurnExecutionResult;
import com.javaclaw.runtime.TurnHarness;
import com.javaclaw.runtime.TurnJournal;
import com.javaclaw.runtime.TurnRecoverySnapshot;
import com.javaclaw.server.lifecycle.LifecycleCoordinator;
import com.javaclaw.server.persistence.CoreCommandService;
import com.javaclaw.server.persistence.ManagedWorktreeService;
import com.javaclaw.server.persistence.TurnStartRequest;

/** 使用虚拟线程异步执行 Thin Turn Harness，并按 Turn ID 保证进程内幂等。 */
public final class HarnessTurnDispatcher implements AwaitableTurnDispatcher, AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger(HarnessTurnDispatcher.class);

    private final CoreCommandService core;
    private final ManagedWorktreeService worktrees;
    private final ModelGateway models;
    private final TurnHarness harness;
    private final TurnJournal journal;
    private final TurnCommandFactory commands;
    private final ExecutorService executor;
    private final Clock clock;
    private final LifecycleCoordinator lifecycle;
    private final CanonicalJson json;
    private final Map<TurnId, Execution> executions = new ConcurrentHashMap<>();

    /**
     * 创建调度器并接管模型网关的关闭责任。
     *
     * @param services Core、Profile、绑定和权限权威服务
     * @param resources Harness、模型、日志和 lease 运行资源
     * @param systemInstruction 已审阅系统说明
     * @param clock 平台时钟
     * @param json 规范 JSON codec，用于恢复已完成 Turn 摘要
     */
    public HarnessTurnDispatcher(
            TurnPlatformServices services,
            RuntimeResources resources,
            String systemInstruction,
            Clock clock,
            CanonicalJson json) {
        Objects.requireNonNull(services, "services");
        this.core = services.core();
        worktrees = services.worktrees();
        Objects.requireNonNull(resources, "resources");
        models = resources.models();
        harness = resources.harness();
        journal = resources.journal();
        commands = new TurnCommandFactory(services, models, resources.catalogs(), systemInstruction, json);
        this.clock = Objects.requireNonNull(clock, "clock");
        this.json = Objects.requireNonNull(json, "json");
        lifecycle = resources.lifecycle();
        executor = Executors.newThreadPerTaskExecutor(
                Thread.ofVirtual().name("javaclaw-turn-", 0).factory());
    }

    @Override
    public TurnStartRequest resolve(CoreRpcContracts.TurnStartPayload request, CorePayloads.Message message) {
        return commands.resolve(request, message);
    }

    @Override
    public TurnStartRequest resolveOrchestrated(
            CoreRpcContracts.TurnStartPayload request,
            CorePayloads.Message message,
            AutomationExecutionSnapshot snapshot) {
        return commands.resolveOrchestrated(request, message, snapshot);
    }

    TurnExecutionCommand bindOrchestrated(
            AgentTurn turn, CoreRpcContracts.TurnStartPayload request, AutomationExecutionSnapshot snapshot) {
        return commands.createOrchestrated(turn, request, snapshot);
    }

    @Override
    public AutomationExecutionSnapshot freeze(
            WorkspaceId workspaceId, com.javaclaw.api.ExecutionOverrides execution, CancellationToken cancellation) {
        return commands.freezeAutomation(workspaceId, execution, cancellation);
    }

    AutomationExecutionSnapshot freezeChild(AgentTurn parent, com.javaclaw.api.ExecutionOverrides selection) {
        return commands.freezeChild(parent, selection);
    }

    void dispatchChild(
            AgentTurn turn, CoreRpcContracts.TurnStartPayload request, AutomationExecutionSnapshot snapshot) {
        ensureDispatched(turn, request, Optional.of(snapshot));
    }

    @Override
    public void dispatch(AgentTurn turn, CoreRpcContracts.TurnStartPayload request) {
        ensureDispatched(turn, request, Optional.empty());
    }

    /** {@inheritDoc} */
    @Override
    public void resume(TurnId turnId) {
        AgentTurn turn = core.findTurn(Objects.requireNonNull(turnId, "turnId"))
                .orElseThrow(() -> new IllegalArgumentException("persisted Turn does not exist"));
        if (turn.status() != TurnStatus.QUEUED && turn.status() != TurnStatus.RUNNING) {
            return;
        }
        CorePayloads.Message message = core.turnUserMessage(turn.id());
        CoreRpcContracts.TurnStartPayload request = new CoreRpcContracts.TurnStartPayload(
                turn.threadId(), AgentConfigurationResolver.overrides(core.resolvedConfig(turn.id())), message.text());
        try {
            ensureDispatched(turn, request, Optional.empty());
        } catch (RuntimeException failure) {
            recordRecoveryFailure(turn, failure);
        }
    }

    /**
     * 启动时恢复全部排队或硬崩溃遗留的 Turn。
     *
     * @return 已提交到 Harness 的 Turn 数
     */
    public int resumePersisted() {
        int resumed = 0;
        for (AgentTurn turn : core.listRecoverableTurns()) {
            resume(turn.id());
            if (executions.containsKey(turn.id())) {
                resumed++;
            }
        }
        return resumed;
    }

    /**
     * 提交或复用进程内执行，并以短等待周期传播上层取消。
     *
     * <p>实现说明：等待不会占用平台线程；虚拟线程每 100 毫秒检查一次父级取消。已进入终态的 Turn 从 Item 日志恢复可见文本， usage 因未作为在线状态持久化而返回 0。
     */
    @Override
    public TurnExecutionResult dispatchAndAwait(
            AgentTurn turn, CoreRpcContracts.TurnStartPayload request, CancellationToken cancellation)
            throws Exception {
        Objects.requireNonNull(cancellation, "cancellation");
        AgentTurn current = core.findTurn(turn.id()).orElseThrow();
        if (isTerminal(current.status())) {
            return recoverTerminal(current);
        }
        Execution execution = ensureDispatched(current, request, Optional.empty());
        if (execution == null) {
            return recoverTerminal(core.findTurn(turn.id()).orElseThrow());
        }
        return await(turn.id(), execution, cancellation);
    }

    @Override
    public TurnExecutionResult dispatchOrchestratedAndAwait(
            AgentTurn turn,
            CoreRpcContracts.TurnStartPayload request,
            AutomationExecutionSnapshot snapshot,
            CancellationToken cancellation)
            throws Exception {
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(cancellation, "cancellation");
        AgentTurn current = core.findTurn(turn.id()).orElseThrow();
        if (isTerminal(current.status())) {
            return recoverTerminal(current);
        }
        Execution execution = ensureDispatched(current, request, Optional.of(snapshot));
        if (execution == null) {
            return recoverTerminal(core.findTurn(turn.id()).orElseThrow());
        }
        return await(turn.id(), execution, cancellation);
    }

    private Execution ensureDispatched(
            AgentTurn turn, CoreRpcContracts.TurnStartPayload request, Optional<AutomationExecutionSnapshot> snapshot) {
        Objects.requireNonNull(turn, "turn");
        Objects.requireNonNull(request, "request");
        AgentTurn current = core.findTurn(turn.id())
                .orElseThrow(() -> new IllegalArgumentException("persisted Turn does not exist"));
        // 已占用执行槽的重试沿用当前执行，不能因模型配置撤销而重新绑定或错误终结活动 Turn。
        Execution active = executions.get(current.id());
        if (active != null) {
            return active;
        }
        if (current.status() != TurnStatus.QUEUED && current.status() != TurnStatus.RUNNING) {
            AgentTurn refreshed = core.findTurn(current.id()).orElseThrow();
            active = executions.get(current.id());
            if (active != null) {
                return active;
            }
            if (isTerminal(refreshed.status())) {
                return null;
            }
            throw new IllegalStateException("Turn is not dispatchable: " + refreshed.status());
        }
        TurnExecutionCommand command = snapshot.map(value -> commands.createOrchestrated(current, request, value))
                .orElseGet(() -> commands.create(current, request));
        Execution candidate = new Execution(lifecycle.acquireActivity(
                "turn:" + current.id(), "TURN", current.budget().wallTime().plusMinutes(5)));
        Execution existing = executions.putIfAbsent(current.id(), candidate);
        if (existing != null) {
            candidate.closeLease();
            return existing;
        }

        Optional<AgentTurn> claimed = recheckClaimedTurn(current.id(), candidate);
        if (claimed.isEmpty()) {
            return null;
        }
        AgentTurn executing = claimed.orElseThrow();

        boolean worktreeRunning = false;
        try {
            // 只有取得执行槽并复核权威 Turn 后才能推进 Worktree，
            // 避免终态重试产生伪状态转换。
            worktrees.markRunning(executing.threadId());
            worktreeRunning = true;
            candidate.task(executor.submit(() -> run(command, candidate)));
        } catch (RuntimeException failure) {
            releaseCandidate(current.id(), candidate);
            if (worktreeRunning) {
                worktrees.finishExecution(executing.threadId(), TurnStatus.FAILED);
            }
            throw failure;
        }
        return candidate;
    }

    private Optional<AgentTurn> recheckClaimedTurn(TurnId turnId, Execution candidate) {
        AgentTurn current;
        try {
            current = core.findTurn(turnId).orElseThrow();
        } catch (RuntimeException failure) {
            releaseCandidate(turnId, candidate);
            throw failure;
        }
        if (isTerminal(current.status())) {
            releaseCandidate(turnId, candidate);
            return Optional.empty();
        }
        if (current.status() != TurnStatus.QUEUED && current.status() != TurnStatus.RUNNING) {
            releaseCandidate(turnId, candidate);
            throw new IllegalStateException("Turn is not dispatchable: " + current.status());
        }
        return Optional.of(current);
    }

    private void releaseCandidate(TurnId turnId, Execution candidate) {
        executions.remove(turnId, candidate);
        candidate.closeLease();
    }

    private TurnExecutionResult await(TurnId turnId, Execution execution, CancellationToken cancellation)
            throws Exception {
        while (true) {
            if (cancellation.isCancelled()) {
                cancel(turnId, cancellation.reason().orElse("父级编排已取消"));
            }
            try {
                return execution.completion().get(100, TimeUnit.MILLISECONDS);
            } catch (TimeoutException ignored) {
                // 短轮询只用于传播协作式取消；执行仍由 Harness 虚拟线程推进。
            } catch (ExecutionException failure) {
                Throwable cause = failure.getCause();
                if (cause instanceof Exception exception) {
                    throw exception;
                }
                throw new IllegalStateException("Turn completion failed", cause);
            }
        }
    }

    /**
     * 请求取消活动 Turn。
     *
     * @param turnId Turn
     * @param reason 脱敏原因
     * @return 是否首次发布取消
     */
    @Override
    public void cancel(TurnId turnId, String reason) {
        Execution execution = executions.get(Objects.requireNonNull(turnId, "turnId"));
        if (execution != null) {
            execution.cancellation().cancel(reason);
            return;
        }
        AgentTurn current = core.findTurn(turnId).orElseThrow();
        if (current.status() == TurnStatus.QUEUED || current.status() == TurnStatus.RUNNING) {
            journal.transition(turnId, current.status(), TurnStatus.CANCELLED, Optional.empty());
        }
    }

    private void run(TurnExecutionCommand command, Execution execution) {
        try {
            CancellationToken cancellation = core.parentTurn(command.turn().threadId())
                    .<CancellationToken>map(
                            parent -> new ParentTurnCancellation(core, parent.id(), execution.cancellation(), clock))
                    .orElse(execution.cancellation());
            TurnExecutionResult result = harness.execute(command, cancellation);
            execution.completion().complete(result);
        } catch (Exception failure) {
            LOGGER.error("Turn {} escaped the Harness boundary", command.turn().id(), failure);
            TurnExecutionResult result = recordDispatchFailure(command);
            execution.completion().complete(result);
        } finally {
            finishWorktree(command.turn());
            executions.remove(command.turn().id(), execution);
            execution.closeLease();
        }
    }

    private void finishWorktree(AgentTurn turn) {
        try {
            AgentTurn current = core.findTurn(turn.id()).orElseThrow();
            if (isTerminal(current.status())) {
                worktrees.finishExecution(current.threadId(), current.status());
            }
        } catch (RuntimeException failure) {
            LOGGER.error("Turn {} 的 Managed Worktree 终态同步失败", turn.id(), failure);
        }
    }

    private TurnExecutionResult recordDispatchFailure(TurnExecutionCommand command) {
        TurnId turnId = command.turn().id();
        String code = "TURN_DISPATCH_FAILED";
        try {
            AgentTurn current = core.findTurn(turnId).orElseThrow();
            if (current.status() == TurnStatus.QUEUED) {
                journal.beginOrRecover(command);
                current = core.findTurn(turnId).orElseThrow();
            }
            if (current.status() == TurnStatus.RUNNING) {
                CorePayloads.Error error =
                        new CorePayloads.Error(code, "Turn 调度失败", true, Instant.now(clock), Map.of());
                journal.append(turnId, "error", CoreSchemas.ERROR, error, ItemStatus.FAILED);
                journal.transition(turnId, TurnStatus.RUNNING, TurnStatus.FAILED, Optional.of(code));
            }
        } catch (RuntimeException persistenceFailure) {
            LOGGER.error("Turn {} dispatch failure could not be recorded", turnId, persistenceFailure);
        }
        return new TurnExecutionResult(
                turnId, TurnStatus.FAILED, "", ModelUsage.zero(), 0, Optional.empty(), Optional.of(code));
    }

    private TurnExecutionResult recoverTerminal(AgentTurn turn) {
        TurnRecoverySnapshot recovery = turn.status() == TurnStatus.CANCELLED
                ? journal.findRecovery(turn.id()).orElseGet(TurnRecoverySnapshot::initial)
                : journal.readRecovery(turn.id());
        return new TurnExecutionResult(
                turn.id(),
                turn.status(),
                recovery.assistantText(),
                recovery.usage(),
                recovery.toolCalls(),
                Optional.empty(),
                turn.errorCode());
    }

    private void recordRecoveryFailure(AgentTurn turn, RuntimeException failure) {
        String code = "TURN_RECOVERY_BLOCKED";
        try {
            AgentTurn current = core.findTurn(turn.id()).orElseThrow();
            if (current.status() == TurnStatus.QUEUED) {
                journal.transition(current.id(), TurnStatus.QUEUED, TurnStatus.RUNNING, Optional.empty());
                current = core.findTurn(current.id()).orElseThrow();
            }
            if (current.status() == TurnStatus.RUNNING) {
                CorePayloads.Error error = new CorePayloads.Error(
                        code,
                        "Turn 冻结依赖无法安全恢复",
                        false,
                        Instant.now(clock),
                        Map.of("failureType", failure.getClass().getSimpleName()));
                journal.append(current.id(), "error", CoreSchemas.ERROR, error, ItemStatus.FAILED);
                journal.transition(current.id(), TurnStatus.RUNNING, TurnStatus.FAILED, Optional.of(code));
            }
        } catch (RuntimeException persistenceFailure) {
            failure.addSuppressed(persistenceFailure);
            LOGGER.error("Turn {} recovery failure could not be recorded", turn.id(), persistenceFailure);
        }
        LOGGER.warn("Turn {} restart recovery was blocked by frozen dependency validation", turn.id());
    }

    private static boolean isTerminal(TurnStatus status) {
        return status == TurnStatus.COMPLETED || status == TurnStatus.CANCELLED || status == TurnStatus.FAILED;
    }

    /**
     * 取消活动执行、释放虚拟线程执行器并关闭模型客户端。
     *
     * <p>实现说明：先发送协作式取消并等待事务落盘；仅在五秒后仍未退出时才中断线程，避免在 H2 文件 IO 中直接注入中断。
     */
    @Override
    public void close() throws Exception {
        executions.values().forEach(execution -> execution.cancellation().cancel("App Server 正在关闭"));
        executor.shutdown();
        if (!awaitTermination()) {
            executions.values().forEach(Execution::interrupt);
            executor.shutdownNow();
            awaitTermination();
        }
        executions.values().forEach(Execution::closeLease);
        if (models instanceof AutoCloseable closeable) {
            closeable.close();
        }
    }

    private boolean awaitTermination() {
        try {
            boolean terminated = executor.awaitTermination(5, TimeUnit.SECONDS);
            if (!terminated) {
                LOGGER.warn("Turn executor did not terminate within five seconds");
            }
            return terminated;
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static final class Execution {
        private final CancellationSource cancellation = new CancellationSource();
        private final CompletableFuture<TurnExecutionResult> completion = new CompletableFuture<>();
        private final LifecycleCoordinator.Lease lease;
        private volatile Future<?> task;

        private Execution(LifecycleCoordinator.Lease lease) {
            this.lease = Objects.requireNonNull(lease, "lease");
        }

        private CancellationSource cancellation() {
            return cancellation;
        }

        private CompletableFuture<TurnExecutionResult> completion() {
            return completion;
        }

        private void task(Future<?> value) {
            task = Objects.requireNonNull(value, "value");
        }

        private void interrupt() {
            Future<?> current = task;
            if (current != null) {
                current.cancel(true);
            }
        }

        private void closeLease() {
            lease.close();
        }
    }

    /**
     * Turn 执行阶段共享的不可变资源。
     *
     * @param models 模型路由，关闭调度器时一并关闭
     * @param harness Thin Harness
     * @param journal 失败兜底日志
     * @param lifecycle 活动 Turn lease 协调器
     * @param catalogs 工具目录冻结端口
     */
    public record RuntimeResources(
            ModelGateway models,
            TurnHarness harness,
            TurnJournal journal,
            LifecycleCoordinator lifecycle,
            com.javaclaw.runtime.ToolCatalogPort catalogs) {
        /** 校验运行资源。 */
        public RuntimeResources {
            Objects.requireNonNull(models, "models");
            Objects.requireNonNull(harness, "harness");
            Objects.requireNonNull(journal, "journal");
            Objects.requireNonNull(lifecycle, "lifecycle");
            Objects.requireNonNull(catalogs, "catalogs");
        }
    }
}
