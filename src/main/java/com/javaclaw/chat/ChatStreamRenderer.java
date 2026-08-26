package com.javaclaw.chat;

import com.javaclaw.config.AgentConfig;
import com.javaclaw.ui.javafx.loop.LoopStatusView;
import com.javaclaw.ui.javafx.loop.LoopStatusViewFactory;
import javafx.scene.Node;
import javafx.scene.layout.HBox;
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
    private final ExpandableMarkdownBlockFactory expandableBlocks;
    private final LoopStatusViewFactory loopStatusViews;
    private final ChatInlineImageRenderer inlineImages;
    private final Supplier<String> modelName;

    private AssistantMessageView assistantMessage;
    private String activeToolName;
    private ExpandableMarkdownBlockView toolResultBlock;
    private ExpandableMarkdownBlockView planAgentBlock;
    private String planAgentName;
    private final StringBuilder planAgentBuffer = new StringBuilder();
    private final Set<String> displayedImagePaths = new HashSet<>();
    private LoopStatusView loopStatusView;
    private String finalPlanDraft;

    ChatStreamRenderer(
            ChatSessionController transcript,
            ChatComposerController composer,
            ThinkingPanelController thinking,
            AssistantMessageFactory assistantMessages,
            ExpandableMarkdownBlockFactory expandableBlocks,
            LoopStatusViewFactory loopStatusViews,
            ChatInlineImageRenderer inlineImages,
            Supplier<String> modelName) {
        this.transcript = Objects.requireNonNull(transcript, "transcript");
        this.composer = Objects.requireNonNull(composer, "composer");
        this.thinking = Objects.requireNonNull(thinking, "thinking");
        this.assistantMessages = Objects.requireNonNull(assistantMessages, "assistantMessages");
        this.expandableBlocks = Objects.requireNonNull(expandableBlocks, "expandableBlocks");
        this.loopStatusViews = Objects.requireNonNull(loopStatusViews, "loopStatusViews");
        this.inlineImages = Objects.requireNonNull(inlineImages, "inlineImages");
        this.modelName = Objects.requireNonNull(modelName, "modelName");
    }

    void createMessage(
            Runnable regenerate,
            Consumer<String> save,
            Consumer<AssistantMessageView> delete) {
        loopStatusView = null;
        finalPlanDraft = null;
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
        MarkdownBubble reply = activeReply();
        if (reply == null) {
            return;
        }
        if (reply.getLength() == 0) {
            revealReply();
            composer.setThinkingText("助手正在回复...");
            thinking.setReplying();
        }
        reply.appendText(chunk);
    }

    void appendSubAgent(String toolName, String content, ChunkKind kind) {
        if (assistantMessage == null) {
            return;
        }
        assistantMessage.showTools();
        String displayName = displayName(toolName);
        boolean dynamicTask = "execute_task_agent".equals(toolName);
        if (toolName == null || !toolName.equals(activeToolName)
                || (dynamicTask && kind == ChunkKind.RESULT)) {
            createToolResultBlock(toolName, displayName);
        }
        if (kind == ChunkKind.THINKING) {
            composer.setThinkingText(displayName + " 正在思考...");
            thinking.appendSubAgentThinking(displayName, content);
            return;
        }
        appendToolReply(displayName, content);
        if (toolResultBlock != null) {
            inlineImages.displayInline(
                    content, toolResultBlock.contentHost(), displayedImagePaths);
        }
    }

    void appendPlanHint(String hint) {
        composer.setThinkingText("正在执行规划...");
        thinking.updatePlan(hint);
    }

    void startPlanAgent(String agentName) {
        if (assistantMessage == null) {
            return;
        }
        if (planAgentName != null && !planAgentName.equals(agentName)) {
            finishPlanAgent();
        }
        if (planAgentBlock != null) {
            planAgentBlock.bubble().finish();
        }
        assistantMessage.showTools();
        composer.setThinkingText(agentName + " 正在发言...");
        planAgentName = agentName;
        planAgentBuffer.setLength(0);
        thinking.appendSubAgentThinking(agentName, "");
        planAgentBlock = expandableBlocks.create(
                ExpandableMarkdownBlockFactory.Variant.PLAN_AGENT, agentName, true);
        assistantMessage.toolsHost().getChildren().add(planAgentBlock.root());
    }

    void appendPlanAgentReply(String chunk) {
        if (planAgentBlock == null) {
            return;
        }
        String displayChunk = chunk.replace("[PLAN_COMPLETE]", "");
        if (displayChunk.isEmpty()) {
            return;
        }
        planAgentBlock.bubble().appendText(displayChunk);
        if (planAgentName != null) {
            planAgentBuffer.append(displayChunk);
            thinking.appendSubAgentThinking(planAgentName, displayChunk);
        }
    }

    void finishPlanAgent() {
        if (planAgentName == null) {
            return;
        }
        if (planAgentBlock != null) {
            planAgentBlock.bubble().finish();
        }
        String singleLine = planAgentBuffer.toString().trim().replaceAll("\\s+", " ");
        String summary = singleLine.isEmpty()
                ? "（无回复内容）"
                : singleLine.length() > 80
                        ? singleLine.substring(0, 77) + "..." : singleLine;
        thinking.markSubAgentResult(planAgentName, summary);
        planAgentName = null;
        planAgentBuffer.setLength(0);
    }

    void updateLoopStatus(
            com.javaclaw.loop.model.LoopStatus status,
            boolean streamDisplayedElsewhere,
            Consumer<Node> suspendedNode) {
        if (loopStatusView == null) {
            loopStatusView = loopStatusViews.create(status);
            HBox row = loopStatusView.root();
            if (streamDisplayedElsewhere) {
                suspendedNode.accept(row);
            } else {
                transcript.addMessage(row);
            }
            return;
        }
        loopStatusView.update(status);
    }

    void setFinalPlanDraft(String draft) {
        finalPlanDraft = draft;
    }

    String currentText(boolean planMode) {
        if (planMode && finalPlanDraft != null && !finalPlanDraft.isBlank()) {
            return finalPlanDraft;
        }
        if (planMode && planAgentBlock != null && planAgentBlock.bubble().getLength() > 0) {
            return planAgentBlock.bubble().getText();
        }
        MarkdownBubble reply = activeReply();
        return reply != null && reply.getLength() > 0 ? reply.getText() : null;
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

    ExpandableMarkdownBlockView planAgentBlock() {
        return planAgentBlock;
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

    void markLoopCancelled() {
        if (loopStatusView != null) {
            loopStatusView.markCancelled();
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
        revealReply();
        if (reply != null) {
            reply.finish();
        }
        if (toolResultBlock != null) {
            toolResultBlock.bubble().finish();
        }
        if (planAgentBlock != null) {
            planAgentBlock.bubble().finish();
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
        activeToolName = null;
        toolResultBlock = null;
        planAgentBlock = null;
        planAgentName = null;
        planAgentBuffer.setLength(0);
        displayedImagePaths.clear();
        finalPlanDraft = null;
        loopStatusView = null;
    }

    static String formatTurnMeta(TurnMetrics metrics, DeliveryState state) {
        String duration = metrics.durationMs() >= 1000
                ? String.format("%.1fs", metrics.durationMs() / 1000.0)
                : metrics.durationMs() + "ms";
        StringBuilder text = new StringBuilder(duration)
                .append(" · ").append(String.format("%,d tok", metrics.totalTokens()));
        if (metrics.cacheReadInputTokens() > 0 || metrics.cacheWriteInputTokens() > 0) {
            text.append(" · 缓存读/写 ")
                    .append(metrics.cacheReadInputTokens()).append('/')
                    .append(metrics.cacheWriteInputTokens());
        }
        if (metrics.reasoningTokens() > 0) {
            text.append(" · 推理 ").append(metrics.reasoningTokens());
        }
        if (state == DeliveryState.CANCELLED) {
            text.append(" · 已取消");
        } else if (state == DeliveryState.FAILED) {
            text.append(" · 失败");
        }
        return text.toString();
    }

    private void createToolResultBlock(String toolName, String displayName) {
        if (toolResultBlock != null) {
            toolResultBlock.bubble().finish();
        }
        activeToolName = toolName;
        toolResultBlock = expandableBlocks.create(
                ExpandableMarkdownBlockFactory.Variant.SUB_AGENT, displayName, false);
        assistantMessage.toolsHost().getChildren().add(toolResultBlock.root());
    }

    private void appendToolReply(String displayName, String content) {
        if (toolResultBlock == null || content == null || content.isBlank()) {
            return;
        }
        toolResultBlock.revealContent();
        composer.setThinkingText(displayName + " 已返回结果...");
        thinking.markSubAgentResult(displayName,
                content.length() > 80 ? content.substring(0, 77) + "..." : content);
        toolResultBlock.bubble().appendText(content);
        log.debug("已追加子智能体回复 [{}]，内容长度: {} 字符", activeToolName, content.length());
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
