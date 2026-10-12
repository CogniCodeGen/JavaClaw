package com.javaclaw.application.agent;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.javaclaw.api.conversation.ConversationCallbacks;
import com.javaclaw.api.conversation.ConversationEvent;
import com.javaclaw.framework.api.RunEventEnvelope;

/** Internal UI draft projection. A canonical terminal reply replaces, rather than appends to, it. */
public final class ConversationReplyProjection {
    private final String segmentId;
    private String modelStepId = "";
    private boolean began;
    private boolean finished;

    public ConversationReplyProjection(String segmentId) { this.segmentId = segmentId; }

    public synchronized boolean onEvent(RunEventEnvelope event, ConversationCallbacks callbacks) {
        String type = event.type();
        if (finished) return type.startsWith("core.model.reply.");
        if (event.schemaVersion() == 1 && "framework.springai".equals(event.producer())) {
            String step = event.payload().path("modelStepId").asText("");
            switch (type) {
                case "core.model.reply.started" -> {
                    if (step.isBlank()) return true;
                    modelStepId = step;
                    began = true;
                    command(callbacks, "begin", "");
                    return true;
                }
                case "core.model.reply.delta" -> {
                    if (began && !step.isBlank() && step.equals(modelStepId)) {
                        String text = event.payload().path("text").asText("");
                        if (!text.isEmpty()) callbacks.onEvent(new ConversationEvent.Reply(text));
                    }
                    return true;
                }
                case "core.model.reply.reset" -> {
                    if (began && step.equals(modelStepId)) command(callbacks, "reset", "");
                    return true;
                }
                case "core.model.retrying", "core.harness.protocol_violation",
                        "core.harness.protocol_repair_requested" -> reset(callbacks);
                default -> { }
            }
        }
        if (type.equals("core.task.repair_requested")) reset(callbacks);
        if (type.equals("core.step.started") && event.payload().path("input").path("phase")
                .asText("").equals("harness.decision_invalid")) reset(callbacks);
        return false;
    }

    public synchronized void canonical(String text, ConversationCallbacks callbacks) {
        if (finished) return;
        finished = true;
        if (began) command(callbacks, "replace", text == null ? "" : text);
        else if (text != null && !text.isBlank()) callbacks.onEvent(new ConversationEvent.Reply(text));
    }

    public synchronized void close() { finished = true; }

    private void reset(ConversationCallbacks callbacks) {
        if (began) command(callbacks, "reset", "");
        modelStepId = "";
    }

    private void command(ConversationCallbacks callbacks, String action, String text) {
        var payload = JsonNodeFactory.instance.objectNode().put("action", action).put("segmentId", segmentId);
        if (action.equals("replace")) payload.put("text", text);
        callbacks.onEvent(new ConversationEvent.Custom("reply_stream", payload));
    }
}
