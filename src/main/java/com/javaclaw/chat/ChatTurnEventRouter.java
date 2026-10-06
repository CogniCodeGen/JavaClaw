package com.javaclaw.chat;

import com.fasterxml.jackson.databind.JsonNode;
import com.javaclaw.agent.clarify.ClarifyPayload;
import com.javaclaw.api.conversation.ConversationEvent;
import com.javaclaw.loop.LoopConstants;
import com.javaclaw.loop.model.LoopStatus;
import com.javaclaw.framework.spi.EffectReceiptV1;
import com.javaclaw.chat.ThinkingContentRenderer.StageState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;

/** Routes typed conversation events to their presentation collaborators. */
final class ChatTurnEventRouter {

    private static final Logger log = LoggerFactory.getLogger(ChatTurnEventRouter.class);

    private final ChatStreamRenderer renderer;
    private final ThinkingPanelController thinking;
    private final Consumer<String> loopDetected;
    private final Consumer<ConversationEvent.Usage> usage;
    private final Consumer<ClarifyPayload> clarification;
    private final Set<String> planningSteps = new HashSet<>();

    ChatTurnEventRouter(
            ChatStreamRenderer renderer,
            ThinkingPanelController thinking,
            Consumer<String> loopDetected,
            Consumer<ConversationEvent.Usage> usage,
            Consumer<ClarifyPayload> clarification) {
        this.renderer = Objects.requireNonNull(renderer, "renderer");
        this.thinking = Objects.requireNonNull(thinking, "thinking");
        this.loopDetected = Objects.requireNonNull(loopDetected, "loopDetected");
        this.usage = Objects.requireNonNull(usage, "usage");
        this.clarification = Objects.requireNonNull(clarification, "clarification");
    }

    void startNewStream() {
        planningSteps.clear();
    }

    void route(ConversationEvent event) {
        switch (event) {
            case ConversationEvent.Thinking value -> renderer.appendThinking(value.chunk());
            case ConversationEvent.Reply value -> renderer.appendReply(value.chunk());
            case ConversationEvent.ToolStarted value -> renderer.appendToolCall(
                    value.toolName(), value.invocationId(), value.input());
            case ConversationEvent.ToolResult value -> renderer.appendToolResult(value);
            case ConversationEvent.ToolFailed value -> renderer.appendToolFailure(
                    value.toolName(), value.invocationId(), value.message());
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
                    stageState(value.status()), value.detail());
            case ConversationEvent.Custom value -> routeCustom(value);
            default -> log.debug("忽略未注册的 UI 事件投影: {}", event.getClass().getName());
        }
    }

    private void routeCustom(ConversationEvent.Custom event) {
        if ("reply_stream".equals(event.kind())) {
            JsonNode payload = event.payload();
            if (!payload.isObject()) return;
            JsonNode action = payload.path("action");
            JsonNode segmentId = payload.path("segmentId");
            if (!action.isTextual() || !segmentId.isTextual()) return;
            JsonNode text = payload.path("text");
            renderer.updateReplyStream(action.asText(), segmentId.asText(),
                    text.isTextual() ? text.asText() : null);
        } else if ("plan_final".equals(event.kind()) && event.payload().isTextual()) {
            renderer.setFinalPlanDraft(event.payload().asText());
        } else if ("clarify_request".equals(event.kind())) {
            ClarifyPayload.fromJson(event.payload()).ifPresent(clarification);
        } else if (LoopConstants.EVENT_STATUS_KIND.equals(event.kind())) {
            LoopStatus.fromJson(event.payload()).ifPresent(renderer::updateLoopStatus);
        } else if ("core.tool.receipt".equals(event.kind())) {
            // A completed call only means the Java tool method returned. The trusted
            // receipt describes what the operation actually established.
            JsonNode payload = event.payload();
            thinking.updateToolReceipt(payload.path("tool").asText(""),
                    payload.path("invocationId").asText(""),
                    receiptStatus(payload.path("status")),
                    payload.path("reason").asText(""), payload.path("metadata"));
        } else if (event.kind().startsWith("core.step.")) {
            routePlanningStep(event.kind(), event.payload());
        } else {
            log.debug("收到自定义事件 [{}] {}", event.kind(), event.payload());
        }
    }

    private void routePlanningStep(String kind, JsonNode payload) {
        String id = payload.path("stepId").asText("");
        if (id.isBlank()) return;
        if (kind.equals("core.step.started")) {
            String phase = payload.path("input").path("phase").asText("");
            String label = switch (phase) {
                case "select", "select_v2" -> "选择上下文";
                case "repair_select_sources", "repair_select_sources_v2" -> "校正资料来源";
                case "refine", "refine_v2" -> "筛选候选资料";
                default -> null;
            };
            if (label == null) return;
            String detail = switch (phase) {
                case "select", "select_v2" -> "正在确定本轮需要的资料和工具";
                case "repair_select_sources", "repair_select_sources_v2" ->
                        "正在从可用的上下文来源中重新选择";
                default -> "正在核对检索结果与工具候选";
            };
            planningSteps.add(id);
            thinking.recordPipelineProgress(id, label, StageState.RUNNING, detail);
        } else if (kind.equals("core.step.completed") || kind.equals("core.step.failed")) {
            if (!planningSteps.remove(id)) return;
            boolean failed = kind.equals("core.step.failed");
            String detail = failed ? payload.path("message").asText("") : null;
            thinking.recordPipelineProgress(id, "上下文规划",
                    failed ? StageState.ERROR : StageState.DONE, detail);
        }
    }

    private static StageState stageState(ConversationEvent.Progress.Status status) {
        if (status == null) return StageState.RUNNING;
        return switch (status) {
            case RUNNING -> StageState.RUNNING;
            case DONE -> StageState.DONE;
            case SKIPPED -> StageState.SKIPPED;
            case ERROR -> StageState.ERROR;
        };
    }

    private static EffectReceiptV1.Status receiptStatus(JsonNode status) {
        if (!status.isTextual()) return null;
        try { return EffectReceiptV1.Status.valueOf(status.asText()); }
        catch (IllegalArgumentException unknown) { return null; }
    }
}
