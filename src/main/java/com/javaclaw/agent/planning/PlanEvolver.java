package com.javaclaw.agent.planning;

import com.javaclaw.agent.TokenTracker;
import com.javaclaw.agent.evaluation.EvaluationResult;
import com.javaclaw.prompt.PlanEvolvePrompts;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatModelBase;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 计划演进器（GEPA 之 Plan）— 计划版本管理与统一演进入口。
 *
 * <p>本类替代旧 {@code AdaptivePlanningEngine}，名字反映其新职责：
 * 服务于聊天 GEPA 实时评估、任务模式 Critic 驳回、用户主动反馈三类调用，
 * 通过统一的 {@link #evolve(EvolveRequest)} 入口按 {@link EvolveTrigger}
 * 选择不同的提示词模板。</p>
 *
 * <p>计划版本以快照形式持久化（旧实现），版本历史通过 {@link #getPlanHistory()}
 * 暴露。{@code PlanVersion} 当前为快照对象；后续若长任务版本爆量，可改为 diff 链。</p>
 */
public class PlanEvolver {

    private static final Logger log = LoggerFactory.getLogger(PlanEvolver.class);

    public enum EvolveTrigger {
        /** GEPA 中段评估触发（聊天模式） */
        EVAL_DRIVEN,
        /** 质疑智能体驳回触发（任务模式） */
        CHALLENGE_DRIVEN,
        /** 用户 restartWithCorrections 主动反馈触发 */
        USER_CORRECTION
    }

    /**
     * 计划演进请求 — 由调用方按触发场景填充。
     *
     * @param trigger        触发来源
     * @param task           原始任务描述
     * @param currentPlan    当前计划文本（可空，空时由模型从零产出）
     * @param hint           触发场景给出的具体指引（评估摘要 / 质疑反馈 / 用户输入）
     * @param remainingSteps 剩余待执行步骤的描述（聊天模式可能为空）
     */
    public record EvolveRequest(EvolveTrigger trigger,
                                String task,
                                String currentPlan,
                                String hint,
                                String remainingSteps) {
        public EvolveRequest {
            if (trigger == null) trigger = EvolveTrigger.EVAL_DRIVEN;
            task = task == null ? "" : task;
            currentPlan = currentPlan == null ? "" : currentPlan;
            hint = hint == null ? "" : hint;
            remainingSteps = remainingSteps == null ? "" : remainingSteps;
        }
    }

    private final ChatModelBase model;
    private final GenerateOptions generateOptions;
    private final List<PlanVersion> planHistory = new ArrayList<>();
    private int versionCounter = 0;
    /** 计划演进模型调用的 token 用量上报；null 时跳过统计 */
    private final TokenTracker tokenTracker;

    public PlanEvolver(ChatModelBase model) {
        this(model, null);
    }

    public PlanEvolver(ChatModelBase model, TokenTracker tokenTracker) {
        this.model = model;
        this.tokenTracker = tokenTracker;
        this.generateOptions = GenerateOptions.builder().build();
    }

    /** 重置计划历史（每次新会话/新任务开始前调用） */
    public synchronized void reset() {
        planHistory.clear();
        versionCounter = 0;
    }

    /** 记录新计划版本（首次规划时调用） */
    public synchronized PlanVersion recordPlan(String planContent, String reason) {
        PlanVersion version = new PlanVersion(++versionCounter, planContent, reason);
        planHistory.add(version);
        log.info("计划 v{} 已记录: {}", versionCounter, reason);
        return version;
    }

    /** 当前最新计划版本 */
    public synchronized Optional<PlanVersion> getCurrentPlan() {
        if (planHistory.isEmpty()) return Optional.empty();
        return Optional.of(planHistory.get(planHistory.size() - 1));
    }

    /** 完整版本历史的只读副本 */
    public synchronized List<PlanVersion> getPlanHistory() {
        return List.copyOf(planHistory);
    }

    /**
     * 统一演进入口 — 三类调用方共用。
     *
     * <p>按 {@link EvolveTrigger} 选择系统提示词模板，调用模型产出修正后的计划文本，
     * 自动追加为新 {@link PlanVersion} 进入历史。失败时返回 empty，调用方应回退到
     * 当前版本继续执行。</p>
     */
    public Optional<PlanVersion> evolve(EvolveRequest req) {
        if (req == null || req.task().isBlank()) return Optional.empty();
        try {
            String sysPrompt = systemPromptOf(req.trigger());
            String userContent = buildUserPrompt(req);

            Msg sysMsg = Msg.builder().role(MsgRole.SYSTEM).name("system").textContent(sysPrompt).build();
            Msg userMsg = Msg.builder().role(MsgRole.USER).name("user").textContent(userContent).build();

            String response = streamCollect(sysMsg, userMsg);
            if (response.isBlank()) return Optional.empty();

            String reason = reasonOf(req.trigger(), req.hint());
            return Optional.of(recordPlan(response, reason));
        } catch (Exception e) {
            log.warn("计划演进失败: {}", e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * 中段评估驱动的计划调整 —— GEPA 评估发现执行偏离时的快捷入口。
     *
     * <p>把 {@link EvaluationResult} 翻译成 {@code hint} 字符串，调用 {@link #evolve}
     * 走 {@link EvolveTrigger#EVAL_DRIVEN} 模板。evaluation 为空或不需修正时返回 empty。</p>
     */
    public Optional<PlanVersion> evolveFromEvaluation(String task, EvaluationResult evaluation) {
        if (evaluation == null || !evaluation.isNeedsCorrection()) return Optional.empty();
        String hint = "评分 " + String.format("%.1f", evaluation.getScore()) + "/5.0｜"
                + evaluation.getSummary()
                + (evaluation.getSuggestions().isEmpty()
                        ? "" : "｜建议：" + String.join("；", evaluation.getSuggestions()));
        Optional<PlanVersion> current = getCurrentPlan();
        return evolve(new EvolveRequest(
                EvolveTrigger.EVAL_DRIVEN,
                task,
                current.map(PlanVersion::getContent).orElse(""),
                hint,
                ""));
    }

    /** 构建注入编排器提示词的计划历史摘要 */
    public synchronized String buildPlanHistoryPrompt() {
        if (planHistory.isEmpty()) return "";
        StringBuilder sb = new StringBuilder("\n## 计划调整历史\n\n");
        for (PlanVersion v : planHistory) {
            sb.append("- v").append(v.getVersion())
              .append(" (").append(v.getCreatedAt()).append("): ")
              .append(v.getChangeReason()).append("\n");
        }
        return sb.toString();
    }

    // ==================== 内部辅助 ====================

    private static String systemPromptOf(EvolveTrigger trigger) {
        return switch (trigger) {
            case EVAL_DRIVEN -> PlanEvolvePrompts.EVAL_DRIVEN_SYS_PROMPT;
            case CHALLENGE_DRIVEN -> PlanEvolvePrompts.CHALLENGE_DRIVEN_SYS_PROMPT;
            case USER_CORRECTION -> PlanEvolvePrompts.USER_CORRECTION_SYS_PROMPT;
        };
    }

    private static String buildUserPrompt(EvolveRequest req) {
        StringBuilder sb = new StringBuilder();
        sb.append("## 原始任务\n").append(req.task()).append("\n\n");
        if (!req.currentPlan().isBlank()) {
            sb.append("## 当前计划\n").append(req.currentPlan()).append("\n\n");
        }
        sb.append("## 触发指引\n").append(req.hint().isBlank() ? "（无）" : req.hint()).append("\n\n");
        if (!req.remainingSteps().isBlank()) {
            sb.append("## 剩余待执行步骤\n").append(req.remainingSteps()).append("\n");
        }
        return sb.toString();
    }

    private static String reasonOf(EvolveTrigger trigger, String hint) {
        String prefix = switch (trigger) {
            case EVAL_DRIVEN -> "中段评估调整";
            case CHALLENGE_DRIVEN -> "质疑驳回重规划";
            case USER_CORRECTION -> "用户反馈修订";
        };
        if (hint == null || hint.isBlank()) return prefix;
        String trimmed = hint.length() > 60 ? hint.substring(0, 60) + "..." : hint;
        return prefix + " — " + trimmed;
    }

    private String streamCollect(Msg sys, Msg user) {
        StringBuilder sb = new StringBuilder();
        List<ChatResponse> responses = model.stream(
                List.of(sys, user), List.of(), generateOptions
        ).collectList().block();
        if (responses == null) return "";
        for (ChatResponse resp : responses) {
            if (resp.getContent() == null) continue;
            for (ContentBlock block : resp.getContent()) {
                if (block instanceof TextBlock tb && tb.getText() != null) {
                    sb.append(tb.getText());
                }
            }
        }
        if (tokenTracker != null) {
            long[] usage = TokenTracker.extractUsage(responses);
            tokenTracker.recordModelUsage("PlanEvolver", usage[0], usage[1]);
        }
        return sb.toString().trim();
    }
}
