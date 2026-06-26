package com.javaclaw.agent.evaluation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.javaclaw.agent.TokenTracker;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatModelBase;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.scheduler.Schedulers;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * 过程评估流水线：在流式执行过程中周期性触发中间评估，实现 GEPA 中的 Evaluate 阶段
 *
 * <p>每完成 N 次真实工具调用（不含子智能体思考流），触发一次异步评估。
 * 评估在后台线程执行，通过 onEvaluation 回调将结果推送到 UI。
 * 当评分低于阈值时，needsCorrection 为 true，供 AdaptivePlanningEngine 触发计划调整。</p>
 */
public class EvaluationPipeline {

    private static final Logger log = LoggerFactory.getLogger(EvaluationPipeline.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String SYS_PROMPT = """
            你是执行过程评估专家。根据任务目标和已执行的工具调用历史，评估当前执行进度和质量。

            评估维度（各 1~5 分）：
            - 目标达成度：已完成步骤与整体目标的吻合程度
            - 结果完整性：各步骤结果是否完整、有效
            - 执行效率：有无冗余操作或资源浪费
            综合评分 = 三维均值（保留一位小数）。

            严格返回 JSON：
            {"score": 3.5, "summary": "评估摘要（50字内）", "needsCorrection": false, "suggestions": ["建议1", "建议2"]}
            """;

    private final ChatModelBase model;
    private final int intervalTasks;
    private final double threshold;
    private final int maxFeedbackRounds;
    private final GenerateOptions generateOptions;
    /** 评估模型调用的 token 用量上报；null 时跳过统计 */
    private final TokenTracker tokenTracker;

    private final AtomicInteger toolCallCount = new AtomicInteger(0);
    private final AtomicInteger feedbackRounds = new AtomicInteger(0);
    private final List<String> toolCallHistory = new ArrayList<>();
    private volatile String currentTask = "";

    public EvaluationPipeline(ChatModelBase model, int intervalTasks, double threshold, int maxFeedbackRounds) {
        this(model, null, intervalTasks, threshold, maxFeedbackRounds);
    }

    public EvaluationPipeline(ChatModelBase model, TokenTracker tokenTracker,
                              int intervalTasks, double threshold, int maxFeedbackRounds) {
        this.model = model;
        this.tokenTracker = tokenTracker;
        this.intervalTasks = intervalTasks;
        this.threshold = threshold;
        this.maxFeedbackRounds = maxFeedbackRounds;
        this.generateOptions = GenerateOptions.builder().build();
    }

    /**
     * 重置评估状态（每次 streamChat 开始前调用）
     */
    public void reset(String task) {
        toolCallCount.set(0);
        feedbackRounds.set(0);
        synchronized (toolCallHistory) { toolCallHistory.clear(); }
        this.currentTask = task;
    }

    /**
     * 记录一次工具调用，当达到触发阈值时异步启动评估。
     *
     * <p>上游调用方（ChatService）只把真正的 {@code ConversationEvent.ToolResult}
     * 送进本流水线，子智能体事件已在上游剥离。</p>
     *
     * @param toolName     工具名称
     * @param result       工具调用结果
     * @param onEvaluation 评估结果回调（评估在后台线程执行）
     */
    public void recordToolCall(String toolName, String result, Consumer<EvaluationResult> onEvaluation) {
        String entry = "[" + toolName + "] " + (result.length() > 200 ? result.substring(0, 200) + "..." : result);
        synchronized (toolCallHistory) { toolCallHistory.add(entry); }

        int count = toolCallCount.incrementAndGet();
        if (count % intervalTasks == 0
                && feedbackRounds.get() < maxFeedbackRounds
                && onEvaluation != null) {
            triggerAsync(onEvaluation, count + " 次工具调用间隔");
        }
    }

    /**
     * 强制触发一次评估（不受调用间隔限制，用于连续失败等紧急场景）
     *
     * <p>已达到最大反馈轮数时静默忽略。</p>
     *
     * @param onEvaluation 评估结果回调
     */
    public void forceEvaluate(Consumer<EvaluationResult> onEvaluation) {
        if (feedbackRounds.get() >= maxFeedbackRounds || onEvaluation == null) return;
        triggerAsync(onEvaluation, "连续失败强制触发");
    }

    private void triggerAsync(Consumer<EvaluationResult> onEvaluation, String reason) {
        List<String> snapshot;
        synchronized (toolCallHistory) { snapshot = new ArrayList<>(toolCallHistory); }
        String task = currentTask;
        feedbackRounds.incrementAndGet();
        log.info("触发第 {} 轮过程评估（{}）", feedbackRounds.get(), reason);

        Schedulers.boundedElastic().schedule(() -> {
            EvaluationResult evalResult = evaluate(task, snapshot);
            if (evalResult != null) {
                onEvaluation.accept(evalResult);
            }
        });
    }

    private EvaluationResult evaluate(String task, List<String> history) {
        try {
            String historyText = String.join("\n", history);
            String userContent = "## 任务目标\n" + task + "\n\n## 已执行步骤\n" + historyText;

            Msg sysMsg = Msg.builder().role(MsgRole.SYSTEM).name("system").textContent(SYS_PROMPT).build();
            Msg userMsg = Msg.builder().role(MsgRole.USER).name("user").textContent(userContent).build();

            StringBuilder responseText = new StringBuilder();
            List<ChatResponse> responses = model.stream(
                    List.of(sysMsg, userMsg), List.of(), generateOptions
            ).collectList().block();

            if (responses != null) {
                for (ChatResponse resp : responses) {
                    if (resp.getContent() != null) {
                        for (ContentBlock block : resp.getContent()) {
                            if (block instanceof TextBlock tb) responseText.append(tb.getText());
                        }
                    }
                }
            }
            if (tokenTracker != null) {
                long[] usage = TokenTracker.extractUsage(responses);
                tokenTracker.recordModelUsage("EvaluationPipeline", usage[0], usage[1]);
            }
            return parse(responseText.toString().trim());
        } catch (Exception e) {
            log.warn("过程评估模型调用失败: {}", e.getMessage());
            return null;
        }
    }

    private EvaluationResult parse(String raw) {
        try {
            String json = raw.trim();
            if (json.startsWith("```")) {
                int start = json.indexOf('{');
                int end = json.lastIndexOf('}');
                if (start >= 0 && end > start) json = json.substring(start, end + 1);
            }
            JsonNode root = MAPPER.readTree(json);
            double score = root.path("score").asDouble(3.0);
            String summary = root.path("summary").asText("");
            boolean needsCorrection = root.path("needsCorrection").asBoolean(false) || score < threshold;
            List<String> suggestions = new ArrayList<>();
            JsonNode sugNode = root.get("suggestions");
            if (sugNode != null && sugNode.isArray()) {
                for (JsonNode s : sugNode) {
                    if (s.isTextual()) suggestions.add(s.asText());
                }
            }
            log.info("过程评估完成 — 评分: {}/5.0, 需修正: {}", String.format("%.1f", score), needsCorrection);
            return new EvaluationResult(score, summary, needsCorrection, suggestions);
        } catch (Exception e) {
            log.warn("解析评估结果失败: {}", e.getMessage());
            return null;
        }
    }
}
