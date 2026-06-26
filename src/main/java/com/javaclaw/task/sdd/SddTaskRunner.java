package com.javaclaw.task.sdd;

import com.javaclaw.agent.model.ModelFactory;
import com.javaclaw.skill.SkillManager;
import com.javaclaw.task.sdd.agent.AgentScopeCriticJudge;
import com.javaclaw.task.sdd.agent.AgentScopeSddAgents;
import com.javaclaw.task.sdd.agent.ProcessCommandRunner;
import com.javaclaw.task.sdd.gate.AutoApproveReviewGate;
import com.javaclaw.task.sdd.spec.SpecStore;
import com.javaclaw.task.sdd.verify.ScenarioVerifier;

import java.util.Map;
import java.util.function.BooleanSupplier;

/**
 * SDD 任务的<b>装配与运行入口</b> —— 把真相层、验证层、编排器、AgentScope 智能体、命令执行器、
 * critic、评审闸门组装成一个可运行单元。
 *
 * <p>这是 B5d 接缝层调用的统一入口：legacy {@code TaskManager}（或未来任何前端）只需提供运行期
 * 协作者（{@link ModelFactory}、能力工具表、{@link SkillManager}、token 汇聚、{@link ReviewGate}、
 * {@link SddProgress}）与一个 {@link TaskContext}，即可驱动完整的 SDD 生命周期，无需感知内部装配。</p>
 *
 * <p>{@link #run()} 同步阻塞返回 {@link SddOutcome}（调用方在后台线程驱动）；{@link #cancel()}
 * 在阶段/循环边界生效。</p>
 *
 * @author JavaClaw
 */
public final class SddTaskRunner {

    private final SddOrchestrator orchestrator;
    private final SpecStore store;
    private final AgentScopeSddAgents agents;
    /** 验证层子件引用：用于把核验超时与实现/结构化阶段超时对齐（避免默认 120s 误杀慢构建/慢 critic）。 */
    private final ProcessCommandRunner commandRunner;
    private final AgentScopeCriticJudge critic;

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
        this.store = new SpecStore(ctx.workDir());
        this.agents = new AgentScopeSddAgents(modelFactory, capabilityTools, skills, tokenSink);
        this.commandRunner = new ProcessCommandRunner();
        this.critic = new AgentScopeCriticJudge(ctx.workDir(), modelFactory, tokenSink);
        ScenarioVerifier verifier = new ScenarioVerifier(ctx.workDir(), commandRunner, critic);
        this.orchestrator = new SddOrchestrator(ctx, store, verifier, agents,
                gate == null ? new AutoApproveReviewGate() : gate,
                progress == null ? SddProgress.NOOP : progress)
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
        return orchestrator.run();
    }

    /** 从既有 change 续跑（恢复中断任务）。 */
    public SddOutcome resume() {
        return orchestrator.resume();
    }

    public void cancel() {
        orchestrator.cancel();
    }

    /** 暴露真相层，供调用方读取 change 状态（进度、tasks 勾选、归档等）用于 UI 渲染/恢复。 */
    public SpecStore store() {
        return store;
    }
}
