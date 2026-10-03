package com.javaclaw.framework.springai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.javaclaw.framework.api.AgentStep;
import com.javaclaw.framework.api.StepId;
import com.javaclaw.framework.core.ReasoningRequest;
import com.javaclaw.framework.core.RunStepQuery;
import com.javaclaw.framework.core.StepEvents;
import com.javaclaw.framework.core.ToolInvocationFingerprint;
import com.javaclaw.framework.core.ToolRecoveryRequiredException;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static com.javaclaw.framework.springai.ModelStepJournal.LEGACY_DESKTOP_REOBSERVE;

/** Replays pre-observation-token desktop calls only as non-executing repair feedback. */
final class LegacyDesktopBatchRecovery {
    private final ReasoningRequest request;
    private final RunStepQuery steps;
    private final PersistedProviderTools providerTools;
    private final ObjectMapper json;

    LegacyDesktopBatchRecovery(ReasoningRequest request, RunStepQuery steps,
            PersistedProviderTools providerTools, ObjectMapper json) {
        this.request = request;
        this.steps = steps;
        this.providerTools = providerTools;
        this.json = json;
    }

    /**
     * An older desktop schema did not require observationId. Its pending action
     * is never invoked during recovery, even if the old provider prompt offered
     * it. This exception to schema-fingerprint validation is safe only when
     * every call is either already completed or still unstarted. Unstarted calls
     * receive durable, non-executing repair feedback.
     */
    boolean legacyDesktopBatch(AgentStep model) {
        if (model.state() != AgentStep.State.COMPLETED || model.output() == null
                || !model.output().has("message") || providerTools.outputRedacted(model.id())) {
            return false;
        }
        Message decoded = StepMessageCodec.message(model.output().path("message"));
        if (!(decoded instanceof AssistantMessage assistant)
                || assistant.getToolCalls().isEmpty()) return false;
        Set<String> offered = new HashSet<>(providerTools.offeredNames(model));
        boolean missingObservation = false;
        for (var call : assistant.getToolCalls()) {
            if (!offered.contains(call.name())) return false;
            StepId id = StepId.tool(request.runId(), invocationId(model.id(), call));
            var persisted = steps.step(request.runId(), id);
            if (persisted.isPresent()) {
                AgentStep previous = persisted.get();
                if (previous.kind() == AgentStep.Kind.TOOL) {
                    if (previous.state() != AgentStep.State.COMPLETED
                            || previous.output() == null
                            || !previous.output().has("modelOutput")) return false;
                } else if (previous.kind() != AgentStep.Kind.ORCHESTRATION
                        || previous.input() == null
                        || !LEGACY_DESKTOP_REOBSERVE.equals(
                                previous.input().path("phase").asText())) {
                    return false;
                }
            }
            if (!desktopFrameAction(call.name())) continue;
            JsonNode arguments;
            try { arguments = json.readTree(call.arguments()); }
            catch (Exception invalid) { return false; }
            if (arguments == null || !arguments.isObject()) return false;
            if (arguments.path("observationId").asText("").isBlank()) {
                missingObservation = true;
            }
        }
        return missingObservation;
    }

    void persistLegacyDesktopBatch(
            AgentStep model, List<AssistantMessage.ToolCall> calls) {
        Set<String> callIds = new HashSet<>();
        for (var call : calls) {
            if (!callIds.add(call.id())) {
                throw recoveryRequired(model,
                        "legacy desktop batch has duplicate call IDs");
            }
            StepId id = StepId.tool(request.runId(), invocationId(model.id(), call));
            var existing = steps.step(request.runId(), id);
            if (existing.isPresent() && !(existing.get().kind() == AgentStep.Kind.TOOL
                    && existing.get().state() == AgentStep.State.COMPLETED
                    && existing.get().output() != null
                    && existing.get().output().has("modelOutput"))
                    && !validLegacyDesktopFeedback(model, call, existing.get())) {
                throw recoveryRequired(model,
                        "legacy desktop batch has a started or changed call; "
                                + "input outcome needs reconciliation");
            }
        }
        for (var call : calls) {
            StepId id = StepId.tool(request.runId(), invocationId(model.id(), call));
            if (steps.step(request.runId(), id).isPresent()) continue;
            var input = JsonNodeFactory.instance.objectNode();
            input.put("phase", LEGACY_DESKTOP_REOBSERVE);
            input.put("modelStepId", model.id().value());
            input.put("callId", call.id());
            input.put("toolName", call.name());
            input.put("callFingerprint", legacyCallFingerprint(call));
            if (desktopFrameAction(call.name())) {
                JsonNode arguments = parse(call.arguments());
                String sessionId = arguments.path("sessionId").asText("");
                if (sessionId.matches("[A-Za-z0-9_-]{1,128}")) {
                    input.put("sessionId", sessionId);
                }
            }
            StepEvents.started(request.events(), id, AgentStep.Kind.ORCHESTRATION,
                    input, model.id().value());
            var output = JsonNodeFactory.instance.objectNode();
            output.put("legacyDesktopReobserve", true);
            output.set("modelOutput", legacyDesktopFeedback(call));
            output.put("durationMillis", 0);
            StepEvents.completed(request.events(), id, output, null);
        }
    }

    boolean rejectedLegacyDesktopBatch(AgentStep model) {
        if (model.state() != AgentStep.State.COMPLETED || model.output() == null
                || !model.output().has("message")) return false;
        Message decoded = StepMessageCodec.message(model.output().path("message"));
        if (!(decoded instanceof AssistantMessage assistant)
                || assistant.getToolCalls().isEmpty()) return false;
        List<AgentStep> persisted = new ArrayList<>();
        for (var call : assistant.getToolCalls()) {
            steps.step(request.runId(), StepId.tool(request.runId(),
                    invocationId(model.id(), call))).ifPresent(persisted::add);
        }
        if (persisted.stream().noneMatch(step -> step.kind() == AgentStep.Kind.ORCHESTRATION
                && step.input() != null && LEGACY_DESKTOP_REOBSERVE.equals(
                        step.input().path("phase").asText()))) {
            return false;
        }
        if (persisted.size() != assistant.getToolCalls().size()) {
            throw recoveryRequired(model, "legacy desktop repair batch is incomplete");
        }
        for (var call : assistant.getToolCalls()) {
            StepId id = StepId.tool(request.runId(), invocationId(model.id(), call));
            AgentStep step = steps.step(request.runId(), id).orElseThrow();
            if (step.kind() == AgentStep.Kind.TOOL
                    && step.state() == AgentStep.State.COMPLETED
                    && step.output() != null && step.output().has("modelOutput")) continue;
            if (!validLegacyDesktopFeedback(model, call, step)) {
                throw recoveryRequired(model, "legacy desktop repair batch has changed");
            }
        }
        return true;
    }

    private boolean validLegacyDesktopFeedback(
            AgentStep model, AssistantMessage.ToolCall call, AgentStep step) {
        return step.kind() == AgentStep.Kind.ORCHESTRATION
                && step.state() == AgentStep.State.COMPLETED
                && step.input() != null && step.output() != null
                && LEGACY_DESKTOP_REOBSERVE.equals(step.input().path("phase").asText())
                && model.id().value().equals(step.input().path("modelStepId").asText())
                && call.id().equals(step.input().path("callId").asText())
                && call.name().equals(step.input().path("toolName").asText())
                && legacyCallFingerprint(call).equals(
                        step.input().path("callFingerprint").asText())
                && step.output().path("legacyDesktopReobserve").asBoolean(false)
                && legacyDesktopFeedback(call).equals(step.output().path("modelOutput"))
                && !providerTools.inputRedacted(step.id()) && !providerTools.outputRedacted(step.id());
    }

    private static boolean desktopFrameAction(String name) {
        return switch (name) {
            case "desktop_session_click", "desktop_session_type", "desktop_session_key",
                    "desktop_session_scroll" -> true;
            default -> false;
        };
    }

    private static String legacyCallFingerprint(AssistantMessage.ToolCall call) {
        var value = JsonNodeFactory.instance.objectNode();
        value.put("id", call.id());
        value.put("name", call.name());
        value.put("arguments", call.arguments());
        return ToolInvocationFingerprint.create(LEGACY_DESKTOP_REOBSERVE, value);
    }

    private static JsonNode legacyDesktopFeedback(AssistantMessage.ToolCall call) {
        return JsonNodeFactory.instance.objectNode()
                .put("error", "legacy_desktop_observation_required")
                .put("tool", call.name())
                .put("executed", false)
                .put("message", "This invocation was not executed. "
                        + "A desktop action in this batch used an older schema without observationId. "
                        + "Observe the bound desktop session again, then plan a fresh action "
                        + "using the returned observationId. Do not replay the old call.");
    }

    private static String invocationId(StepId model, AssistantMessage.ToolCall call) {
        return "model/" + model.value() + "/" + call.id();
    }

    private JsonNode parse(String value) {
        try { return json.readTree(value); }
        catch (Exception failure) { throw new IllegalStateException("invalid persisted tool arguments", failure); }
    }

    private static ToolRecoveryRequiredException recoveryRequired(AgentStep model, String reason) {
        return new ToolRecoveryRequiredException(model.id().value(), reason);
    }
}
