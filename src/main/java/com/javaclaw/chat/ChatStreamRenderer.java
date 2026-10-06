package com.javaclaw.chat;

import com.javaclaw.api.conversation.ConversationEvent;
import com.javaclaw.config.AgentConfig;
import com.javaclaw.loop.model.LoopStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Owns the dynamic nodes and transient text state for one active chat stream.
 *
 * <p>All methods are JavaFX-thread confined. The renderer never starts work or persists messages;
 * it only updates FXML-defined message containers and exposes the resulting text to the turn
 * coordinator. {@link #clear(TurnMetrics, DeliveryState)} releases detached dynamic views.</p>
 */
final class ChatStreamRenderer {

    enum ChunkKind { THINKING, REPLY, RESULT }

    private static final Logger log = LoggerFactory.getLogger(ChatStreamRenderer.class);

    private final ChatSessionController transcript;
    private final ChatComposerController composer;
    private final ThinkingPanelController thinking;
    private final AssistantMessageFactory assistantMessages;
    private final ChatInlineImageRenderer inlineImages;
    private final Supplier<String> modelName;

    private AssistantMessageView assistantMessage;
    private final StringBuilder replyBuffer = new StringBuilder();
    private String replySegmentId;
    private int replySegmentOffset;
    private String planAgentName;
    private final StringBuilder planAgentBuffer = new StringBuilder();
    private String planAgentFallback;
    private final Set<String> displayedImagePaths = new HashSet<>();
    private String finalPlanDraft;
    private String activeToolName;

    ChatStreamRenderer(
            ChatSessionController transcript,
            ChatComposerController composer,
            ThinkingPanelController thinking,
            AssistantMessageFactory assistantMessages,
            ChatInlineImageRenderer inlineImages,
            Supplier<String> modelName) {
        this.transcript = Objects.requireNonNull(transcript, "transcript");
        this.composer = Objects.requireNonNull(composer, "composer");
        this.thinking = Objects.requireNonNull(thinking, "thinking");
        this.assistantMessages = Objects.requireNonNull(assistantMessages, "assistantMessages");
        this.inlineImages = Objects.requireNonNull(inlineImages, "inlineImages");
        this.modelName = Objects.requireNonNull(modelName, "modelName");
    }

    void createMessage(
            Runnable regenerate,
            Consumer<String> save,
            Consumer<AssistantMessageView> delete) {
        finalPlanDraft = null;
        planAgentFallback = null;
        replyBuffer.setLength(0);
        replySegmentId = null;
        replySegmentOffset = 0;
        planAgentBuffer.setLength(0);
        activeToolName = null;
        displayedImagePaths.clear();
        ChatMessage timestamp = new ChatMessage(ChatMessage.Role.ASSISTANT, "");
        AssistantMessageView message = assistantMessages.create(
                AgentConfig.AGENT_NAME, modelName.get(), timestamp.getFormattedTime());
        assistantMessage = message;
        message.setRegenerateAction(regenerate);
        message.setQuoteAction(current -> {
            composer.insertInputAtStart("> " + current.replace("\n", "\n> ") + "\n\n");
            composer.focusInput();
        });
        message.setSaveAction(save);
        message.setDeleteAction(() -> delete.accept(message));
        transcript.addMessage(message.root());
    }

    void appendThinking(String chunk) {
        thinking.appendThinking(chunk);
    }

    void appendReply(String chunk) {
        if (assistantMessage == null || chunk == null || chunk.isEmpty()) {
            return;
        }
        if (replyBuffer.isEmpty()) {
            thinking.setReplying();
        }
        replyBuffer.append(chunk);
        MarkdownBubble reply = activeReply();
        if (reply != null) {
            reply.appendText(chunk);
            revealReply();
        }
    }

    void updateReplyStream(String action, String segmentId, String text) {
        if (assistantMessage == null || action == null
                || segmentId == null || segmentId.isBlank()) return;
        String id = segmentId.strip();
        switch (action) {
            case "begin" -> {
                if (id.equals(replySegmentId)) {
                    replaceReplySegment("");
                } else {
                    replySegmentId = id;
                    replySegmentOffset = replyBuffer.length();
                }
            }
            case "reset" -> {
                if (id.equals(replySegmentId)) replaceReplySegment("");
            }
            case "replace" -> {
                if (text == null) return;
                if (replySegmentId == null) {
                    replySegmentId = id;
                    replySegmentOffset = replyBuffer.length();
                }
                if (id.equals(replySegmentId)) replaceReplySegment(text);
            }
            default -> { }
        }
    }

    private void replaceReplySegment(String text) {
        replyBuffer.setLength(replySegmentOffset);
        if (replyBuffer.isEmpty() && !text.isEmpty()) thinking.setReplying();
        replyBuffer.append(text);
        refreshLiveReply();
    }

    private void refreshLiveReply() {
        MarkdownBubble reply = activeReply();
        if (reply == null) return;
        reply.replaceStreamingText(replyBuffer.toString());
        if (!replyBuffer.isEmpty()) revealReply();
    }

    void appendSubAgent(String toolName, String content, ChunkKind kind) {
        if (assistantMessage == null) {
            return;
        }
        String displayName = displayName(toolName);
        switch (kind) {
            case THINKING -> thinking.appendSubAgentThinking(displayName, content);
            case REPLY -> thinking.appendSubAgentReply(displayName, content, inlineImages);
            case RESULT -> {
                thinking.appendToolResult(toolName, content, inlineImages);
                thinking.completeSubAgentIfPresent(displayName);
                if (Objects.equals(toolName, activeToolName)) activeToolName = null;
                log.debug("子智能体 [{}] 已返回结果，内容长度: {} 字符",
                        toolName, content == null ? 0 : content.length());
            }
        }
    }

    void appendToolCall(String toolName, String invocationId, String input) {
        activeToolName = toolName;
        thinking.appendToolCall(toolName, invocationId, input,
                ThinkingContentRenderer.ToolState.RUNNING);
    }

    void appendToolResult(ConversationEvent.ToolResult result) {
        if (assistantMessage == null) return;
        thinking.appendToolResult(result.toolName(), result.invocationId(),
                result.result(), result.output(), result.status(), inlineImages);
        thinking.completeSubAgentIfPresent(displayName(result.toolName()), result.status());
        if (Objects.equals(result.toolName(), activeToolName)) activeToolName = null;
    }

    void appendToolFailure(String toolName, String invocationId, String message) {
        if (assistantMessage == null) return;
        thinking.appendToolFailure(toolName, invocationId, message);
        thinking.completeSubAgentIfPresent(displayName(toolName),
                com.javaclaw.framework.api.ToolExecutionStatus.FAILED);
        if (Objects.equals(toolName, activeToolName)) activeToolName = null;
    }

    void appendPlanHint(String hint) {
        if (hint != null) thinking.updatePlan(hint);
    }

    void startPlanAgent(String agentName) {
        if (assistantMessage == null) {
            return;
        }
        if (planAgentName != null && !planAgentName.equals(agentName)) {
            finishPlanAgent();
        }
        planAgentName = agentName;
        planAgentFallback = null;
        planAgentBuffer.setLength(0);
        thinking.appendSubAgentThinking(agentName, "");
    }

    void appendPlanAgentReply(String chunk) {
        if (planAgentName == null || chunk == null) {
            return;
        }
        String displayChunk = chunk;
        if (displayChunk.isEmpty()) {
            return;
        }
        planAgentBuffer.append(displayChunk);
        thinking.appendSubAgentReply(planAgentName, displayChunk, inlineImages);
    }

    void finishPlanAgent() {
        if (planAgentName == null) {
            return;
        }
        if (!planAgentBuffer.isEmpty()) {
            planAgentFallback = planAgentBuffer.toString();
        }
        thinking.markSubAgentResult(planAgentName, "");
        planAgentName = null;
        planAgentBuffer.setLength(0);
    }

    void updateLoopStatus(LoopStatus status) {
        thinking.recordLoopStatus(status);
    }

    void setFinalPlanDraft(String draft) {
        finalPlanDraft = draft;
    }

    String currentText(boolean planMode) {
        if (planMode && finalPlanDraft != null && !finalPlanDraft.isBlank()) {
            return finalPlanDraft;
        }
        if (planMode && !planAgentBuffer.isEmpty()) {
            return planAgentBuffer.toString();
        }
        if (planMode && planAgentFallback != null) {
            return planAgentFallback;
        }
        return replyBuffer.isEmpty() ? null : replyBuffer.toString();
    }

    void appendLoopWarning(String warning) {
        if (assistantMessage == null) return;
        if (!replyBuffer.isEmpty()) replyBuffer.append("\n\n");
        replyBuffer.append("[循环中断] ").append(warning);
        refreshLiveReply();
        thinking.recordPipelineProgress("loop-warning", "循环检测",
                ThinkingContentRenderer.StageState.ERROR, "已中断循环");
    }

    void showFinalReply(String text) {
        MarkdownBubble reply = activeReply();
        if (reply == null) return;
        reply.finishWith(text);
        revealReply();
    }

    void removePendingMessage() {
        if (assistantMessage == null) return;
        transcript.removeContaining(assistantMessage.root());
        assistantMessage.root().setVisible(false);
        assistantMessage.root().setManaged(false);
    }

    void renderInlineReplyImages() {
        MarkdownBubble reply = activeReply();
        if (reply != null && assistantMessage != null) {
            inlineImages.displayInline(
                    reply.getText(), assistantMessage.replyContentHost(), displayedImagePaths);
        }
    }

    Set<String> displayedImagePaths() {
        return Set.copyOf(displayedImagePaths);
    }

    AssistantMessageView message() {
        return assistantMessage;
    }

    MarkdownBubble activeReply() {
        return assistantMessage == null ? null : assistantMessage.reply();
    }

    void revealReply() {
        if (assistantMessage != null) {
            assistantMessage.revealReply();
        }
    }

    void hideReplyCard() {
        if (assistantMessage != null) {
            assistantMessage.hideReplyCard();
        }
    }

    boolean isActiveMessage(AssistantMessageView message) {
        return message == assistantMessage;
    }

    AssistantMessageView abandon(TurnMetrics metrics, DeliveryState state) {
        AssistantMessageView abandoned = assistantMessage;
        clear(metrics, state);
        return abandoned;
    }

    void clear(TurnMetrics metrics, DeliveryState state) {
        AssistantMessageView message = assistantMessage;
        MarkdownBubble reply = activeReply();
        if (reply != null) {
            reply.finish();
        }
        if (message != null) {
            message.setMetadata(formatTurnMeta(metrics, state));
        }
        resetReferences();
        if (message != null && message.root().getParent() == null) {
            message.close();
        }
    }

    void resetReferences() {
        assistantMessage = null;
        replyBuffer.setLength(0);
        replySegmentId = null;
        replySegmentOffset = 0;
        planAgentName = null;
        planAgentBuffer.setLength(0);
        planAgentFallback = null;
        displayedImagePaths.clear();
        finalPlanDraft = null;
        activeToolName = null;
    }

    static String formatTurnMeta(TurnMetrics metrics, DeliveryState state) {
        String duration = metrics.durationMs() >= 1000
                ? String.format("%.1fs", metrics.durationMs() / 1000.0)
                : metrics.durationMs() + "ms";
        StringBuilder text = new StringBuilder(duration)
                .append(" · ").append(String.format("%,d tok", metrics.totalTokens()));
        if (state == DeliveryState.CANCELLED) {
            text.append(" · 已取消");
        } else if (state == DeliveryState.FAILED) {
            text.append(" · 失败");
        }
        return text.toString();
    }

    private static String displayName(String toolName) {
        if (toolName == null) return "专家回复";
        return switch (toolName) {
            case "coding_expert" -> "编程专家";
            case "knowledge_expert" -> "知识专家";
            case "web_expert" -> "Web浏览专家";
            case "email_expert" -> "邮件专家";
            case "system_expert" -> "系统操作专家";
            case "notification_expert" -> "通知专家";
            case "task_evaluator" -> "任务评估专家";
            case "execute_task_agent" -> "任务智能体";
            default -> toolName.contains("expert") ? toolName : "专家回复 [" + toolName + "]";
        };
    }
}
