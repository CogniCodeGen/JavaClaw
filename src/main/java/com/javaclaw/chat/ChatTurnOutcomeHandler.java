package com.javaclaw.chat;

import com.javaclaw.api.conversation.ConversationOutcome;
import com.javaclaw.framework.api.BudgetExceededException;
import com.javaclaw.framework.api.TurnPausedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.text.NumberFormat;
import java.util.Locale;
import java.util.Objects;

/** Renders and persists the single terminal outcome of a conversation turn. */
final class ChatTurnOutcomeHandler {

    private static final Logger log = LoggerFactory.getLogger(ChatTurnOutcomeHandler.class);

    private final ThinkingPanelController thinking;
    private final ChatStreamRenderer renderer;
    private final ChatTurnController.Host host;

    ChatTurnOutcomeHandler(
            ThinkingPanelController thinking,
            ChatStreamRenderer renderer,
            ChatTurnController.Host host) {
        this.thinking = Objects.requireNonNull(thinking, "thinking");
        this.renderer = Objects.requireNonNull(renderer, "renderer");
        this.host = Objects.requireNonNull(host, "host");
    }

    void complete(
            boolean plan,
            ChatSession target,
            TurnMetrics metrics,
            Runnable finishUi) {
        AssistantMessageView rendered = renderer.message();
        if (plan) renderer.finishPlanAgent();
        try {
            String text = renderer.currentText(plan);
            if (text == null && !plan) {
                text = "[模型未返回有效回复]";
            }
            if (text == null || text.isBlank()) {
                renderer.removePendingMessage();
            } else {
                renderer.showFinalReply(text);
                renderer.renderInlineReplyImages();
            }
            if (text != null && !text.isBlank() && target != null) {
                ChatMessage message = message(text, DeliveryState.COMPLETE, metrics);
                renderer.displayedImagePaths().forEach(message::addImagePath);
                if (rendered != null) {
                    rendered.enableAdoption(() -> host.adoptAssistantMessage(message));
                }
                host.storeAssistantMessage(target, message);
            }
        } catch (RuntimeException failure) {
            log.error("保存回复时发生错误", failure);
        } finally {
            finishUi.run();
        }
    }

    void awaitInput(boolean plan, ChatSession target, TurnMetrics metrics, Runnable finishUi) {
        if (plan) renderer.finishPlanAgent();
        String partial = renderer.currentText(plan);
        if (partial != null && !partial.isBlank()) {
            renderer.showFinalReply(partial);
            renderer.renderInlineReplyImages();
            if (target != null) {
                ChatMessage message = message(partial, DeliveryState.COMPLETE, metrics);
                renderer.displayedImagePaths().forEach(message::addImagePath);
                host.storeAssistantMessage(target, message);
            }
        } else {
            renderer.removePendingMessage();
        }
        finishUi.run();
    }

    void fail(
            Throwable error,
            boolean plan,
            ChatSession target,
            TurnMetrics metrics,
            Runnable finishUi) {
        FailurePresentation presentation = failurePresentation(error);
        if (presentation.budgetStop()) {
            log.warn("模型调用因运行预算停止", error);
        } else if (presentation.paused()) {
            log.warn("运行已暂停，需核对后恢复", error);
        } else {
            log.error("流式输出发生错误", error);
        }
        if (plan) renderer.finishPlanAgent();
        thinking.endStreamFailed();
        try {
            String detail = presentation.detail();
            String errorMessage = (presentation.budgetStop()
                    ? "调用已停止: " : presentation.paused()
                    ? "调用已暂停: " : "调用失败: ") + detail;
            String partial = renderer.currentText(plan);
            if (partial != null && !partial.isBlank() && target != null) {
                String failedText = partial + "\n\n> ⚠ "
                        + (presentation.budgetStop() ? "已停止："
                        : presentation.paused() ? "已暂停：" : "失败：") + detail;
                renderer.showFinalReply(failedText);
                renderer.renderInlineReplyImages();
                ChatMessage message = message(failedText, DeliveryState.FAILED, metrics);
                renderer.displayedImagePaths().forEach(message::addImagePath);
                host.storeAssistantMessage(target, message);
            } else if (target != null && target != host.currentSession()) {
                renderer.removePendingMessage();
                host.storeSystemMessage(target, errorMessage);
            } else {
                renderer.removePendingMessage();
                host.addStaticMessage(ChatMessage.Role.SYSTEM, errorMessage);
            }
        } catch (RuntimeException displayFailure) {
            log.error("显示错误信息时发生异常", displayFailure);
        } finally {
            finishUi.run();
        }
    }

    void cancel(
            ConversationOutcome.Cancelled cancelled,
            boolean plan,
            ChatSession target,
            TurnMetrics metrics,
            Runnable finishUi) {
        log.info("流式输出已取消 — reason={}, userInitiated={}",
                cancelled.reason(), cancelled.userInitiated());
        if (plan) renderer.finishPlanAgent();
        try {
            String partial = renderer.currentText(plan);
            if (partial != null && !partial.isBlank() && target != null) {
                String stoppedText = partial + "\n\n> ⏹ 已停止";
                renderer.showFinalReply(stoppedText);
                renderer.renderInlineReplyImages();
                ChatMessage message = message(stoppedText, DeliveryState.CANCELLED, metrics);
                renderer.displayedImagePaths().forEach(message::addImagePath);
                host.storeAssistantMessage(target, message);
            } else {
                renderer.removePendingMessage();
            }
        } finally {
            finishUi.run();
        }
    }

    void loopDetected(String warning) {
        log.warn("循环检测触发: {}", warning);
        renderer.appendLoopWarning(warning);
    }

    private static ChatMessage message(String text, DeliveryState state, TurnMetrics metrics) {
        ChatMessage message = new ChatMessage(ChatMessage.Role.ASSISTANT, text);
        message.setDeliveryState(state);
        message.setMetrics(metrics);
        return message;
    }

    static boolean isBudgetExceeded(Throwable error) {
        return findBudgetFailure(error) != null;
    }

    static String extractErrorMessage(Throwable error) {
        return failurePresentation(error).detail();
    }

    private static FailurePresentation failurePresentation(Throwable error) {
        TurnPausedException paused = findPausedFailure(error);
        if (paused != null) {
            return new FailurePresentation(false, true, pausedMessage(paused));
        }
        BudgetExceededException budget = findBudgetFailure(error);
        if (budget != null) {
            return new FailurePresentation(true, budgetMessage(budget));
        }
        if (isHttpUnauthorized(error)) {
            return new FailurePresentation(false,
                    "服务返回 HTTP 401，请检查对应服务的凭据或访问权限");
        }
        Throwable cause = error;
        while (cause.getCause() != null) cause = cause.getCause();
        String message = cause.getMessage();
        if (message == null || message.isBlank()) message = cause.getClass().getSimpleName();
        if (cause instanceof java.net.ConnectException) {
            return new FailurePresentation(false, "无法连接到模型服务，请检查服务地址");
        }
        if (cause instanceof java.net.http.HttpTimeoutException) {
            return new FailurePresentation(false, "请求超时，模型响应时间过长");
        }
        if (cause instanceof java.net.UnknownHostException) {
            return new FailurePresentation(false, "无法解析服务器地址，请检查网络连接");
        }
        if (cause instanceof javax.net.ssl.SSLException) {
            return new FailurePresentation(false, "SSL 连接失败，请检查 API 地址");
        }
        String lower = message.toLowerCase(java.util.Locale.ROOT);
        if (lower.contains("429") || lower.contains("rate limit")) {
            return new FailurePresentation(false, "请求频率超限，请稍后再试");
        }
        if (lower.contains("quota")) {
            return new FailurePresentation(false, "API 额度不足，请检查账户余额");
        }
        if (lower.contains("model") && lower.contains("not found")) {
            return new FailurePresentation(false, "模型名称不存在");
        }
        if (lower.contains("503") || lower.contains("service unavailable")) {
            return new FailurePresentation(false, "模型服务暂时不可用");
        }
        return new FailurePresentation(false, message);
    }

    private static TurnPausedException findPausedFailure(Throwable error) {
        for (Throwable current = error; current != null; current = current.getCause()) {
            if (current instanceof TurnPausedException paused) return paused;
        }
        return null;
    }

    private static String pausedMessage(TurnPausedException paused) {
        String detail = paused.getMessage();
        if (detail == null || detail.isBlank()) return "需核对运行状态后恢复";
        String contextPrefix = "planner selected an unauthorized context source: ";
        if (detail.startsWith(contextPrefix)) {
            String sourceId = detail.substring(contextPrefix.length()).trim();
            return "上下文规划选择了不可用的来源 " + sourceId
                    + "，请重新发起请求；若持续出现，请检查来源权限";
        }
        return detail;
    }

    private static boolean isHttpUnauthorized(Throwable error) {
        for (Throwable current = error; current != null; current = current.getCause()) {
            if (current instanceof RestClientResponseException http
                    && http.getStatusCode().value() == 401) return true;
            if (current instanceof WebClientResponseException http
                    && http.getStatusCode().value() == 401) return true;
        }
        return false;
    }

    private static BudgetExceededException findBudgetFailure(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof BudgetExceededException budget) return budget;
            current = current.getCause();
        }
        return null;
    }

    private static String budgetMessage(BudgetExceededException budget) {
        BudgetExceededException.Kind kind = budget.kind();
        String lower = budget.getMessage().toLowerCase(Locale.ROOT);
        if (kind == BudgetExceededException.Kind.UNKNOWN) {
            if (lower.contains("model input token")) {
                kind = BudgetExceededException.Kind.MODEL_INPUT_TOKENS;
            } else if (lower.contains("model output token")) {
                kind = BudgetExceededException.Kind.MODEL_OUTPUT_TOKENS;
            } else if (lower.contains("model cost")) {
                kind = BudgetExceededException.Kind.MODEL_COST;
            } else if (lower.contains("repeated tool-call")) {
                kind = BudgetExceededException.Kind.REPEATED_TOOL_CALLS;
            } else if (lower.contains("tool call")) {
                kind = BudgetExceededException.Kind.TOOL_CALLS;
            }
        }
        return switch (kind) {
            case MODEL_INPUT_TOKENS -> "本轮模型累计输入已达到安全上限"
                    + tokenLimitDetail(budget) + "。请缩小任务范围，或在新一轮中继续。";
            case MODEL_OUTPUT_TOKENS -> "本轮模型累计输出已达到安全上限"
                    + tokenLimitDetail(budget) + "。请缩小任务范围，或在新一轮中继续。";
            case MODEL_COST -> "本轮模型估算成本已达到安全上限"
                    + costLimitDetail(budget) + "。请缩小任务范围，或在新一轮中继续。";
            case TOOL_CALLS -> "本轮工具调用次数已达到安全上限"
                    + integerLimitDetail(budget, " 次") + "。请缩小任务范围后重试。";
            case REPEATED_TOOL_CALLS -> "检测到重复工具调用循环，为避免继续消耗已中止本轮。";
            case UNKNOWN -> "本轮模型累计用量已达到安全上限。请缩小任务范围，或在新一轮中继续。";
        };
    }

    private static String tokenLimitDetail(BudgetExceededException budget) {
        return integerLimitDetail(budget, " token");
    }

    private static String integerLimitDetail(
            BudgetExceededException budget, String unit) {
        if (budget.actual().isBlank() || budget.limit().isBlank()) return "";
        try {
            NumberFormat format = NumberFormat.getIntegerInstance(Locale.CHINA);
            return "（已用 " + format.format(Long.parseLong(budget.actual()))
                    + " / 上限 " + format.format(Long.parseLong(budget.limit())) + unit + "）";
        } catch (NumberFormatException ignored) {
            return "";
        }
    }

    private static String costLimitDetail(BudgetExceededException budget) {
        if (budget.actual().isBlank() || budget.limit().isBlank()) return "";
        try {
            java.math.BigDecimal actual = new java.math.BigDecimal(budget.actual());
            java.math.BigDecimal limit = new java.math.BigDecimal(budget.limit());
            return "（已用 ¥" + actual.stripTrailingZeros().toPlainString()
                    + " / 上限 ¥" + limit.stripTrailingZeros().toPlainString() + "）";
        } catch (NumberFormatException ignored) {
            return "";
        }
    }

    private record FailurePresentation(boolean budgetStop, boolean paused, String detail) {
        private FailurePresentation(boolean budgetStop, String detail) {
            this(budgetStop, false, detail);
        }
    }
}
