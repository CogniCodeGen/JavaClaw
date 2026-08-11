package com.javaclaw.chat;

import com.javaclaw.agent.AgentRuntime;
import com.javaclaw.api.conversation.ConversationOutcome;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.function.Supplier;

/** Renders and persists the single terminal outcome of a conversation turn. */
final class ChatTurnOutcomeHandler {

    private static final Logger log = LoggerFactory.getLogger(ChatTurnOutcomeHandler.class);

    private final Supplier<AgentRuntime> runtime;
    private final ThinkingPanelController thinking;
    private final ChatStreamRenderer renderer;
    private final ChatTurnController.Host host;

    ChatTurnOutcomeHandler(
            Supplier<AgentRuntime> runtime,
            ThinkingPanelController thinking,
            ChatStreamRenderer renderer,
            ChatTurnController.Host host) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
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
        log.error("流式输出发生错误", error);
        if (plan) renderer.finishPlanAgent();
        thinking.endStreamFailed();
        try {
            String detail = runtime.get().extractErrorMessage(error);
            String errorMessage = "调用失败: " + detail;
            String partial = renderer.currentText(plan);
            if (partial != null && !partial.isBlank() && target != null) {
                String failedText = partial + "\n\n> ⚠ 失败：" + detail;
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
}
