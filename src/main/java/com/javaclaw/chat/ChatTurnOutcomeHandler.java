package com.javaclaw.chat;

import com.javaclaw.api.conversation.ConversationOutcome;
import com.javaclaw.framework.api.BudgetExceededException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
        MarkdownBubble reply = renderer.activeReply();
        if (plan) renderer.finishPlanAgent();
        renderer.revealReply();
        try {
            if (plan && reply != null && reply.getLength() == 0) {
                renderer.hideReplyCard();
            } else if (!plan && reply != null && reply.getLength() == 0) {
                reply.finishWith("[模型未返回有效回复]");
            }
            renderer.renderInlineReplyImages();
            String text = renderer.currentText(plan);
            if (text != null && target != null) {
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

    void fail(
            Throwable error,
            boolean plan,
            ChatSession target,
            TurnMetrics metrics,
            Runnable finishUi) {
        FailurePresentation presentation = failurePresentation(error);
        if (presentation.budgetStop()) {
            log.warn("模型调用因运行预算停止", error);
        } else {
            log.error("流式输出发生错误", error);
        }
        if (plan) renderer.finishPlanAgent();
        thinking.endStreamFailed();
        try {
            String detail = presentation.detail();
            String errorMessage = (presentation.budgetStop()
                    ? "调用已停止: " : "调用失败: ") + detail;
            String partial = renderer.currentText(plan);
            if (partial != null && !partial.isBlank() && target != null) {
                String failedText = partial + "\n\n> ⚠ "
                        + (presentation.budgetStop() ? "已停止：" : "失败：") + detail;
                finishRenderedText(failedText, plan);
                host.storeAssistantMessage(
                        target, message(failedText, DeliveryState.FAILED, metrics));
            } else if (target != null && target != host.currentSession()) {
                host.storeSystemMessage(target, errorMessage);
            } else {
                renderer.hideReplyCard();
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
        renderer.revealReply();
        try {
            String partial = renderer.currentText(plan);
            if (partial != null && !partial.isBlank() && target != null) {
                String stoppedText = partial + "\n\n> ⏹ 已停止";
                finishRenderedText(stoppedText, plan);
                host.storeAssistantMessage(
                        target, message(stoppedText, DeliveryState.CANCELLED, metrics));
            } else {
                renderer.hideReplyCard();
            }
            renderer.markLoopCancelled();
        } finally {
            finishUi.run();
        }
    }

    void loopDetected(String warning) {
        log.warn("循环检测触发: {}", warning);
        MarkdownBubble reply = renderer.activeReply();
        if (reply == null) {
            host.addStaticMessage(ChatMessage.Role.SYSTEM, warning);
        } else if (reply.getLength() == 0) {
            reply.finishWith("[循环中断] " + warning);
        } else {
            reply.appendText("\n\n[循环中断] " + warning);
        }
    }

    private void finishRenderedText(String text, boolean plan) {
        MarkdownBubble reply = renderer.activeReply();
        if (!plan && reply != null) {
            reply.finishWith(text);
        } else if (plan && renderer.planAgentBlock() != null) {
            renderer.planAgentBlock().bubble().finishWith(text);
        }
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
        BudgetExceededException budget = findBudgetFailure(error);
        if (budget != null) {
            return new FailurePresentation(true, budgetMessage(budget));
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
        if (lower.contains("401") || lower.contains("unauthorized")) {
            return new FailurePresentation(false, "API 密钥无效或已过期");
        }
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

    private static BudgetExceededException findBudgetFailure(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof BudgetExceededException budget) return budget;
            String message = current.getMessage();
            if (message != null && isLegacyBudgetMessage(message)) {
                return new BudgetExceededException(message);
            }
            current = current.getCause();
        }
        return null;
    }

    private static boolean isLegacyBudgetMessage(String message) {
        String lower = message.toLowerCase(Locale.ROOT);
        return lower.contains(BudgetExceededException.class.getName().toLowerCase(Locale.ROOT))
                || lower.contains("com.javaclaw.framework.core.budgetexceededexception")
                || lower.contains("model usage budget exceeded")
                || lower.contains("model input token budget exceeded")
                || lower.contains("model output token budget exceeded")
                || lower.contains("model cost budget exceeded")
                || lower.contains("tool call budget exceeded")
                || lower.contains("repeated tool-call loop detected");
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
            case MODEL_COST -> "本轮模型成本预算已达到安全上限。请缩小任务范围，或在新一轮中继续。";
            case TOOL_CALLS -> "本轮工具调用预算已达到安全上限。请缩小任务范围后重试。";
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

    private record FailurePresentation(boolean budgetStop, String detail) { }
}
