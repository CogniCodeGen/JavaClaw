package com.javaclaw.loop.agent;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.javaclaw.agent.goal.SuccessCriterion;
import com.javaclaw.agent.model.ModelFactory;
import com.javaclaw.agent.model.ModelTier;
import com.javaclaw.agent.model.StructuredCalls;
import com.javaclaw.loop.CompletionJudge;
import com.javaclaw.loop.LoopConstants;
import com.javaclaw.prompt.LoopPrompts;
import com.javaclaw.task.ValidationInspectionTools;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.memory.autocontext.AutoContextMemory;
import io.agentscope.core.message.Msg;
import io.agentscope.core.tool.Toolkit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * {@link CompletionJudge} 的 AgentScope 实现：描述性/外部检查类目标与准则的独立验收员。
 *
 * <p>仿 {@code AgentScopeCriticJudge}：一个带只读核查工具（{@link ValidationInspectionTools}）
 * 的结构化智能体，进工作目录核查后裁定达成/未达成。判定异常/超时一律保守判未达成，绝不放行。</p>
 */
public final class AgentScopeCompletionJudge implements CompletionJudge {

    private static final Logger log = LoggerFactory.getLogger(AgentScopeCompletionJudge.class);

    private final String workDir;
    private final ModelFactory modelFactory;
    private final long timeoutSeconds;

    /**
     * 自上次 {@link #drainUsedTokens()} 以来累计的模型用量：验收/仲裁调用由控制器逐轮取走
     * 并入循环 TOKEN_BUDGET 护栏。判定调用与取用发生在同一循环线程，AtomicLong 仅为省心。
     * 超时被 dispose 的调用拿不到结果 Msg，其用量无法观测（尽力而为口径）。
     */
    private final java.util.concurrent.atomic.AtomicLong usedTokens =
            new java.util.concurrent.atomic.AtomicLong();

    public AgentScopeCompletionJudge(String workDir, ModelFactory modelFactory) {
        this.workDir = workDir;
        this.modelFactory = modelFactory;
        this.timeoutSeconds = LoopConstants.JUDGE_TIMEOUT_SECONDS;
    }

    @Override
    public Verdict goalMet(String goal, String transcript) {
        String user = "请判断以下目标是否已真正达成：\n"
                + "目标：" + nz(goal) + "\n\n"
                + "迄今进展与现有事实：\n" + nz(transcript) + "\n\n"
                + "工作目录：" + nz(workDir);
        return judge(user);
    }

    @Override
    public Verdict criterionMet(SuccessCriterion criterion, String transcript) {
        String predicate = criterion == null ? "" : nz(criterion.predicate);
        String user = "请判断以下单条成功准则是否成立：\n"
                + "准则：" + predicate + "\n\n"
                + "迄今进展与现有事实：\n" + nz(transcript) + "\n\n"
                + "工作目录：" + nz(workDir);
        return judge(user);
    }

    @Override
    public Verdict progressMade(String goal, String previousOutput, String currentOutput) {
        try {
            // 仲裁是纯文本对比：不挂核查工具、用轻量档模型（区别于达成判定的 HIGH 档）
            ReActAgent arbiter = ReActAgent.builder()
                    .name("循环-进展仲裁")
                    .sysPrompt(LoopPrompts.PROGRESS_ARBITER_SYS_PROMPT)
                    .model(modelFactory.createStructuredChatModel(ModelTier.LIGHT))
                    .maxIters(2)
                    .toolkit(new Toolkit())
                    .memory(buildMemory())
                    .build();

            String user = "目标：\n" + nz(goal)
                    + "\n\n上一轮产出：\n" + nz(previousOutput)
                    + "\n\n本轮产出：\n" + nz(currentOutput)
                    + "\n\n本轮相比上一轮是否实质更接近目标？";
            Msg result = StructuredCalls.blockingCall(arbiter, user, JudgeDraft.class,
                    timeoutSeconds, "进展仲裁");
            meterUsage(result);
            JudgeDraft draft = StructuredCalls.extractStructured(result, JudgeDraft.class);
            if (draft == null) {
                return new Verdict(false, "仲裁未产出结构化结论（停滞判定维持原判）");
            }
            return new Verdict(draft.met, nz(draft.reason));
        } catch (Exception e) {
            log.warn("[循环仲裁] 判定异常（停滞判定维持原判）：{}", e.getMessage());
            return new Verdict(false, "仲裁判定异常：" + e.getMessage());
        }
    }

    private Verdict judge(String userPrompt) {
        try {
            Toolkit toolkit = new Toolkit();
            if (workDir != null && !workDir.isBlank()) {
                toolkit.registerTool(new ValidationInspectionTools(workDir));
            }
            ReActAgent judge = ReActAgent.builder()
                    .name("循环-验收")
                    .sysPrompt(LoopPrompts.JUDGE_SYS_PROMPT)
                    .model(modelFactory.createStructuredChatModel(ModelTier.HIGH))
                    .maxIters(5)
                    .toolkit(toolkit)
                    .memory(buildMemory())
                    .enablePendingToolRecovery(true)
                    .build();

            Msg result = StructuredCalls.blockingCall(judge, userPrompt, JudgeDraft.class,
                    timeoutSeconds, "验收判定");
            meterUsage(result);
            JudgeDraft draft = StructuredCalls.extractStructured(result, JudgeDraft.class);
            if (draft == null) {
                return new Verdict(false, "验收未产出结构化结论（保守判未达成）");
            }
            return new Verdict(draft.met, nz(draft.reason));
        } catch (Exception e) {
            log.warn("[循环验收] 判定异常（保守判未达成）：{}", e.getMessage());
            return new Verdict(false, "验收判定异常：" + e.getMessage());
        }
    }

    @Override
    public long drainUsedTokens() {
        return usedTokens.getAndSet(0);
    }

    /** 从 agent.call 的结果 Msg 累计本次判定的真实模型用量（Msg 携带累计 ChatUsage）。 */
    private void meterUsage(Msg result) {
        if (result == null) return;
        try {
            var u = result.getChatUsage();
            if (u != null) {
                usedTokens.addAndGet(Math.max(0, u.getInputTokens()) + Math.max(0, u.getOutputTokens()));
            }
        } catch (Throwable t) {
            log.debug("读取验收员 ChatUsage 失败，忽略", t);
        }
    }

    private AutoContextMemory buildMemory() {
        return modelFactory.defaultAutoContextMemory();
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    /** 验收结构化输出。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class JudgeDraft {
        @JsonPropertyDescription("目标/准则是否真正达成。务必以实际核查为据，证据不足时判 false，绝不默认通过。")
        @JsonProperty(required = true)
        public boolean met;

        @JsonPropertyDescription("判定理由：引用核查到的具体证据（文件/内容/缺失），一两句话。")
        @JsonProperty(required = true)
        public String reason;
    }
}
