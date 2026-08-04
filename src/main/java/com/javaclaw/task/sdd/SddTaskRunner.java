package com.javaclaw.task.sdd;

import com.javaclaw.agent.model.ModelFactory;
import com.javaclaw.config.AppDatabase;
import com.javaclaw.config.AppDatabaseAccess;
import com.javaclaw.config.DatabaseAccess;
import com.javaclaw.skill.SkillManager;
import com.javaclaw.task.sdd.agent.AgentScopeCriticJudge;
import com.javaclaw.task.sdd.agent.AgentScopeSddAgents;
import com.javaclaw.task.sdd.agent.ProcessCommandRunner;
import com.javaclaw.task.sdd.gate.AutoApproveReviewGate;
import com.javaclaw.task.sdd.spec.OpenSpecChange;
import com.javaclaw.task.sdd.spec.SpecStore;
import com.javaclaw.task.sdd.verify.ScenarioVerifier;

import java.util.Map;
import java.util.function.BooleanSupplier;

/**
 * SDD 任务的<b>装配与运行入口</b> —— 把真相层、验证层、编排器、AgentScope 智能体、命令执行器、
 * critic、评审闸门组装成一个可运行单元。
 *
 * <p>这是 B5d 接缝层调用的统一入口：任务管理器（或未来任何前端）只需提供运行期
 * 协作者（{@link ModelFactory}、能力工具表、{@link SkillManager}、token 汇聚、{@link ReviewGate}、
 * {@link SddProgress}）与一个 {@link TaskContext}，即可驱动完整的 SDD 生命周期，无需感知内部装配。</p>
 *
 * <p>{@link #run()} 同步阻塞返回 {@link SddOutcome}（调用方在后台线程驱动）；{@link #cancel()}
 * 在阶段/循环边界生效。</p>
 *
 * @author JavaClaw
 */
public final class SddTaskRunner implements AutoCloseable {
    private static final com.javaclaw.workflow.model.GraphDefinition SYSTEM_GRAPH =
            com.javaclaw.workflow.service.SystemGraphFactory.sdd();

    private final SddOrchestrator orchestrator;
    private final TaskContext context;
    private final com.javaclaw.workflow.service.WorkflowService workflowService;
    private final SpecStore store;
    private final AgentScopeSddAgents agents;
    /** 验证层子件引用：用于把核验超时与实现/结构化阶段超时对齐（避免默认 120s 误杀慢构建/慢 critic）。 */
    private final ProcessCommandRunner commandRunner;
    private final AgentScopeCriticJudge critic;
    private final Map<String, Object> capabilityTools;

    /**
     * @param ctx             任务上下文
     * @param modelFactory    模型工厂（提供分级模型）
     * @param capabilityTools 能力→工具对象表（web/email/system/notification/command）
     * @param skills          技能管理器（注入 SDD/superpowers 子技能提示；可空）
     * @param tokenSink       token 用量汇聚（按阶段标签 + input,output）；可空
     * @param gate            人机评审闸门（无头用 {@link AutoApproveReviewGate}）
     * @param progress        进度/日志回调；可空（NOOP）
     * @param completionStamp 归档完成时间戳文本（调用方注入，本层不依赖时钟）
     */
    public SddTaskRunner(TaskContext ctx, ModelFactory modelFactory, Map<String, Object> capabilityTools,
                         SkillManager skills, SddTokenSink tokenSink, ReviewGate gate,
                         SddProgress progress, String completionStamp) {
        this(ctx, modelFactory, capabilityTools, skills, tokenSink, gate, progress, completionStamp, null);
    }

    public SddTaskRunner(TaskContext ctx, ModelFactory modelFactory, Map<String, Object> capabilityTools,
                         SkillManager skills, SddTokenSink tokenSink, ReviewGate gate,
                         SddProgress progress, String completionStamp,
                         com.javaclaw.workflow.service.WorkflowService workflowService) {
        this(ctx, modelFactory, capabilityTools, skills, tokenSink, gate, progress, completionStamp,
                workflowService, new AppDatabaseAccess(), AppDatabase.currentWorkspaceId());
    }

    public SddTaskRunner(TaskContext ctx, ModelFactory modelFactory, Map<String, Object> capabilityTools,
                         SkillManager skills, SddTokenSink tokenSink, ReviewGate gate,
                         SddProgress progress, String completionStamp,
                         com.javaclaw.workflow.service.WorkflowService workflowService,
                         DatabaseAccess database, String workspaceId) {
        this.context = ctx;
        this.capabilityTools = capabilityTools == null ? Map.of() : Map.copyOf(capabilityTools);
        this.workflowService = workflowService;
        if (workflowService != null) workflowService.systemGraphs().register(SYSTEM_GRAPH);
        this.store = new SpecStore(ctx.workDir(), database, workspaceId);
        this.agents = new AgentScopeSddAgents(modelFactory, this.capabilityTools, skills, tokenSink);
        this.commandRunner = new ProcessCommandRunner();
        this.critic = new AgentScopeCriticJudge(ctx.workDir(), modelFactory, tokenSink);
        ScenarioVerifier verifier = new ScenarioVerifier(ctx.workDir(), commandRunner, critic);
        this.orchestrator = new SddOrchestrator(ctx, store, verifier, agents,
                gate == null ? new AutoApproveReviewGate() : gate,
                progress == null ? SddProgress.NOOP : progress,
                database, workspaceId)
                .completionStamp(completionStamp == null ? "" : completionStamp);
    }

    /** 注入 token 预算闸门：返回 true 表示预算耗尽，编排器在阶段/循环边界停为待人工。 */
    public SddTaskRunner budgetGuard(BooleanSupplier guard) {
        orchestrator.budgetGuard(guard);
        return this;
    }

    /** 实现项执行的整体阻塞超时（秒）——覆盖单次 executeTask 全程（最多 execMaxIters 轮）。
     *  同时作为核验命令（如 mvn 构建）的执行超时，避免默认 120s 误杀慢构建。 */
    public SddTaskRunner execTimeoutSec(long seconds) {
        if (seconds > 0) {
            agents.execTimeoutSec(seconds);
            commandRunner.setTimeoutSeconds(seconds);
        }
        return this;
    }

    /** 结构化阶段（提案/规格/计划/补做）的阻塞超时（秒）。
     *  同时作为 critic 判定的阻塞超时，确保单次场景核验有界、不挂死编排线程。 */
    public SddTaskRunner structuredTimeoutSec(long seconds) {
        if (seconds > 0) {
            agents.structuredTimeoutSec(seconds);
            critic.timeoutSec(seconds);
        }
        return this;
    }

    /** 实现执行体单项 ReAct 迭代上限。 */
    public SddTaskRunner execMaxIters(int n) {
        if (n > 0) agents.execMaxIters(n);
        return this;
    }

    public SddOutcome run() {
        try {
            return workflowService == null ? orchestrator.run() : runViaGraph(false);
        } finally {
            close();
        }
    }

    /** 从既有 change 续跑（恢复中断任务）。 */
    public SddOutcome resume() {
        try {
            return workflowService == null ? orchestrator.resume() : runViaGraph(true);
        } finally {
            close();
        }
    }

    public void cancel() {
        if (workflowService != null) workflowService.cancelSystem(SYSTEM_GRAPH.id(), context.id());
        orchestrator.cancel();
    }

    /** 关闭本任务私有能力资源（当前主要是隔离浏览器）；可重复调用。 */
    @Override
    public void close() {
        for (Object tool : new java.util.LinkedHashSet<>(capabilityTools.values())) {
            if (tool instanceof AutoCloseable closeable) {
                try {
                    closeable.close();
                } catch (Exception ignored) {
                    // 任务终态不能被资源清理失败覆盖
                }
            }
        }
    }

    private SddOutcome runViaGraph(boolean resume) {
        var outcome = new java.util.concurrent.atomic.AtomicReference<SddOutcome>();
        var error = new java.util.concurrent.atomic.AtomicReference<Throwable>();
        var done = new java.util.concurrent.CountDownLatch(1);
        com.javaclaw.api.conversation.ConversationCallbacks callbacks =
                new com.javaclaw.api.conversation.ConversationCallbacks() {
                    @Override
                    public void onEvent(com.javaclaw.api.conversation.ConversationEvent event) {
                        if (event instanceof com.javaclaw.api.conversation.ConversationEvent.Custom custom
                                && custom.payload() instanceof com.javaclaw.workflow.runtime.GraphEvent.RunFinished finished
                                && workflowService != null) {
                            if (finished.status() == com.javaclaw.workflow.model.RunStatus.CANCELLED) {
                                outcome.compareAndSet(null, SddOutcome.cancelled());
                            } else if (outcome.get() == null) {
                                var saved = workflowService.loadRun(finished.runId());
                                if (saved != null) {
                                    String result = saved.state().get("sdd.result").asText();
                                    String message = saved.state().get("sdd.message").asText();
                                    if (!result.isBlank()) {
                                        outcome.compareAndSet(null, new SddOutcome(
                                                SddOutcome.Result.valueOf(result), message));
                                    }
                                }
                            }
                        }
                    }
                    @Override
                    public void onTerminal(com.javaclaw.api.conversation.ConversationOutcome terminal) {
                        if (terminal instanceof com.javaclaw.api.conversation.ConversationOutcome.Failed failed) {
                            error.set(failed.error());
                        } else if (terminal instanceof com.javaclaw.api.conversation.ConversationOutcome.Cancelled) {
                            outcome.compareAndSet(null, SddOutcome.cancelled());
                        }
                        done.countDown();
                    }
                };
        workflowService.runSystem(SYSTEM_GRAPH, context.id(), resume ? "resume" : "run", callbacks,
                (stageId, graphCtx) -> {
            try (AutoCloseable ignored = graphCtx.cancellation().onCancel(orchestrator::cancel)) {
                if ("prepare".equals(stageId)) {
                    OpenSpecChange existing = resume
                            ? store.readChange(context.slug(), context.id(), context.title())
                            : null;
                    SddOutcome stop = requiresPreparation(resume, existing)
                            ? orchestrator.prepare()
                            : null;
                    if (stop != null) outcome.set(stop);
                    var patch = com.javaclaw.workflow.model.StatePatch.builder()
                            .set("sdd.proceed", stop == null);
                    if (stop != null) {
                        patch.set("sdd.result", stop.result().name())
                                .set("sdd.message", stop.message());
                    }
                    return com.javaclaw.workflow.runtime.NodeResult.next(patch.build());
                }
                if (!"implement".equals(stageId)) {
                    throw new IllegalArgumentException("未知 SDD 系统阶段: " + stageId);
                }
                SddOutcome value = orchestrator.implementPrepared();
                outcome.set(value);
                return com.javaclaw.workflow.runtime.NodeResult.output(
                        com.javaclaw.workflow.model.StatePatch.builder()
                                .set("sdd.result", value.result().name())
                                .set("sdd.message", value.message()).build(), value.message());
            }
        }, com.javaclaw.workflow.service.SystemRecoveryPolicy.RESUME_ONLY);
        try {
            while (!done.await(500, java.util.concurrent.TimeUnit.MILLISECONDS)) { /* 后台管理线程等待图终态 */ }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            cancel();
            return SddOutcome.cancelled();
        }
        if (error.get() != null) return SddOutcome.failed(error.get().getMessage());
        return outcome.get() == null ? SddOutcome.failed("SDD 图未返回结果") : outcome.get();
    }

    static boolean requiresPreparation(boolean resume, OpenSpecChange existing) {
        return !resume || existing == null || existing.tasks().isEmpty();
    }

    /** 暴露真相层，供调用方读取 change 状态（进度、tasks 勾选、归档等）用于 UI 渲染/恢复。 */
    public SpecStore store() {
        return store;
    }
}
