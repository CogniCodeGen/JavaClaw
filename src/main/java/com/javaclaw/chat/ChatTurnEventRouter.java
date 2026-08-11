package com.javaclaw.chat;

import com.javaclaw.agent.clarify.ClarifyPayload;
import com.javaclaw.api.conversation.ConversationEvent;
import com.javaclaw.loop.LoopConstants;
import com.javaclaw.loop.model.LoopStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Routes typed conversation events to their presentation collaborators. */
final class ChatTurnEventRouter {

    private static final Logger log = LoggerFactory.getLogger(ChatTurnEventRouter.class);

    private final ChatStreamRenderer renderer;
    private final ThinkingPanelController thinking;
    private final ChatTurnController.Host host;
    private final Consumer<String> loopDetected;
    private final Consumer<ConversationEvent.Usage> usage;
    private final Consumer<ClarifyPayload> clarification;
    private final BooleanSupplier backgroundStream;

    ChatTurnEventRouter(
            ChatStreamRenderer renderer,
            ThinkingPanelController thinking,
            ChatTurnController.Host host,
            Consumer<String> loopDetected,
            Consumer<ConversationEvent.Usage> usage,
            Consumer<ClarifyPayload> clarification,
            BooleanSupplier backgroundStream) {
        this.renderer = Objects.requireNonNull(renderer, "renderer");
        this.thinking = Objects.requireNonNull(thinking, "thinking");
        this.host = Objects.requireNonNull(host, "host");
        this.loopDetected = Objects.requireNonNull(loopDetected, "loopDetected");
        this.usage = Objects.requireNonNull(usage, "usage");
        this.clarification = Objects.requireNonNull(clarification, "clarification");
        this.backgroundStream = Objects.requireNonNull(backgroundStream, "backgroundStream");
    }

    void route(ConversationEvent event) {
        switch (event) {
            case ConversationEvent.Thinking value -> renderer.appendThinking(value.chunk());
            case ConversationEvent.Reply value -> renderer.appendReply(value.chunk());
            case ConversationEvent.ToolResult value -> renderer.appendSubAgent(
                    value.toolName(), value.result(), ChatStreamRenderer.ChunkKind.RESULT);
            case ConversationEvent.SubAgentThinking value -> renderer.appendSubAgent(
                    value.agentName(), value.chunk(), ChatStreamRenderer.ChunkKind.THINKING);
            case ConversationEvent.SubAgentReply value -> renderer.appendSubAgent(
                    value.agentName(), value.chunk(), ChatStreamRenderer.ChunkKind.REPLY);
            case ConversationEvent.Hint value -> renderer.appendPlanHint(value.text());
            case ConversationEvent.AgentStart value -> renderer.startPlanAgent(value.agentName());
            case ConversationEvent.AgentReply value -> renderer.appendPlanAgentReply(value.chunk());
            case ConversationEvent.Evaluation value ->
                    renderer.appendPlanHint(value.result().formatForDisplay());
            case ConversationEvent.LoopDetected value -> loopDetected.accept(value.warning());
            case ConversationEvent.Usage value -> usage.accept(value);
            case ConversationEvent.Progress value -> thinking.recordPipelineProgress(
                    value.stageId(), value.stageLabel(),
                    value.status() == null ? "running" : value.status().name(), value.detail());
            case ConversationEvent.Custom value -> routeCustom(value);
        }
    }

    private void routeCustom(ConversationEvent.Custom event) {
        if ("plan_final".equals(event.kind()) && event.payload() instanceof String draft) {
            renderer.setFinalPlanDraft(draft);
        } else if ("clarify_request".equals(event.kind())
                && event.payload() instanceof ClarifyPayload value) {
            clarification.accept(value);
        } else if (LoopConstants.EVENT_STATUS_KIND.equals(event.kind())
                && event.payload() instanceof LoopStatus value) {
            renderer.updateLoopStatus(value, backgroundStream.getAsBoolean(), host::suspendStreamNode);
        } else {
            log.debug("收到自定义事件 [{}] {}", event.kind(), event.payload());
        }
    }
}
