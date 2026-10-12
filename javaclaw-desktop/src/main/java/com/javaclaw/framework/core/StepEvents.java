package com.javaclaw.framework.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.javaclaw.framework.api.*;
import com.javaclaw.framework.spi.RunEventDraft;
import com.javaclaw.framework.spi.RunStore;
import java.util.Set;

/** Canonical step envelopes shared by model, tool and orchestration drivers. */
public final class StepEvents {
    private StepEvents() { }
    public static void started(ReasoningEventSink events, StepId id, AgentStep.Kind kind,
                               JsonNode input, String causation) {
        events.emit("core.step.started", 1, "framework.core",
                startedPayload(id, kind, input, causation));
    }

    public static ObjectNode startedPayload(StepId id, AgentStep.Kind kind,
                                            JsonNode input, String causation) {
        ObjectNode payload = base(id);
        payload.put("kind", kind.name());
        payload.set("input", StepPayloadRedactor.redact(input));
        if (!java.util.Objects.equals(input, payload.get("input"))) payload.put("credentialRedacted", true);
        if (causation != null) payload.put("causation", causation);
        return payload;
    }
    public static void completed(ReasoningEventSink events, StepId id, JsonNode output, JsonNode usage) {
        ObjectNode payload = base(id);
        payload.set("output", StepPayloadRedactor.redact(output));
        if (!java.util.Objects.equals(output, payload.get("output"))) payload.put("credentialRedacted", true);
        if (usage != null) payload.set("usage", usage);
        events.emit("core.step.completed", 1, "framework.core", payload);
    }
    public static void failed(ReasoningEventSink events, StepId id, Throwable failure) {
        failed(events, id, failure, null);
    }
    public static void failed(ReasoningEventSink events, StepId id, Throwable failure, JsonNode usage) {
        ObjectNode payload = base(id);
        payload.put("errorType", failure.getClass().getName());
        payload.put("message", com.javaclaw.util.SensitiveDataRedactor.redactText(failure.getMessage()));
        if (usage != null) payload.set("usage", usage);
        events.emit("core.step.failed", 1, "framework.core", payload);
    }
    public static boolean isSettlement(String type) {
        return type.equals("core.step.completed") || type.equals("core.step.failed");
    }
    private static ObjectNode base(StepId id) {
        return JsonNodeFactory.instance.objectNode().put("stepId", id.value());
    }
    public static ReasoningEventSink durableSink(RunStore runs, RunId turnId) {
        return new ReasoningEventSink() {
            @Override public void emit(String type, int version, String producer, JsonNode payload) {
                var stored = runs.find(turnId).orElseThrow(() ->
                        new IllegalStateException("step owner turn does not exist: " + turnId));
                RunEventDraft draft = new RunEventDraft(type, version, producer,
                        stored.request().linkage().correlationId(),
                        payload.path("causation").asText(null), payload);
                if (isSettlement(type)) {
                    if (runs.settleStep(turnId, draft).isEmpty())
                        throw new IllegalStateException("step was not started, already settled, or its thread was deleted: " + turnId);
                    return;
                }
                if (stored.snapshot().state().terminal()) {
                    throw new IllegalStateException("cannot start or append work to terminal turn: " + turnId);
                }
                var appended = runs.append(turnId, Set.of(stored.snapshot().state()),
                        stored.snapshot().state(), draft, null, null);
                if (appended.isEmpty()) throw new IllegalStateException("step owner changed state: " + turnId);
            }

            @Override public void toolStarted(JsonNode stepStarted, JsonNode toolStarted) {
                var stored = runs.find(turnId).orElseThrow(() ->
                        new IllegalStateException("step owner turn does not exist: " + turnId));
                if (stored.snapshot().state().terminal())
                    throw new IllegalStateException("cannot start work on terminal turn: " + turnId);
                String correlation = stored.request().linkage().correlationId();
                var drafts = java.util.List.of(
                        new RunEventDraft("core.step.started", 1, "framework.core", correlation,
                                stepStarted.path("causation").asText(null), stepStarted),
                        new RunEventDraft("core.tool.started", 1, "framework.core", correlation,
                                toolStarted.path("causation").asText(null), toolStarted));
                if (runs.appendBatch(turnId, Set.of(stored.snapshot().state()),
                        stored.snapshot().state(), drafts).isEmpty())
                    throw new IllegalStateException("tool start could not be persisted: " + turnId);
            }
        };
    }
}
