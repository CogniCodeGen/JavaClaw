package com.javaclaw.framework.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.javaclaw.framework.api.RunEventEnvelope;
import com.javaclaw.framework.api.RunId;
import com.javaclaw.framework.api.TaskContractV2;
import com.javaclaw.framework.api.TaskContractV3;
import com.javaclaw.framework.api.TaskOutcome;
import com.javaclaw.framework.spi.EffectCheckpointV1;
import com.javaclaw.framework.spi.EffectReconciliationV1;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Proves an accepted click's declared business postcondition without reconciling uncertain delivery. */
public final class BusinessEffectCheckpointVerifier {
    public static final String EVENT_TYPE = "core.interaction.business_effect_verified";
    private static final String CLICK = "desktop_session_click";
    private static final String OBSERVE = "desktop_session_observe";

    private BusinessEffectCheckpointVerifier() { }

    /** Only one source Run's original, complete host journal may supply these candidates. */
    public static List<EffectCheckpointV1> candidates(RunId sourceRunId,
            List<RunEventEnvelope> sourceEvents, ObjectMapper json) {
        Objects.requireNonNull(json, "json");
        if (!ownOrderedJournal(sourceRunId, sourceEvents)) return List.of();
        var frames = DesktopObservationBaseline.fromEvents(sourceEvents);
        List<EffectCheckpointV1> result = new ArrayList<>();
        for (RunEventEnvelope action : sourceEvents) {
            if (!host(action, "core.tool.receipt", 1) || !acceptedClick(action.payload())) continue;
            String invocation = action.payload().path("invocationId").asText("");
            var started = unique(sourceEvents, "core.tool.started", 1, invocation);
            var completed = unique(sourceEvents, "core.tool.completed", 2, invocation);
            var receipt = unique(sourceEvents, "core.tool.receipt", 1, invocation);
            if (!action.equals(receipt) || !actionTriple(started, completed, action)) continue;
            JsonNode metadata = action.payload().path("metadata");
            var before = frames.stream().filter(frame -> frame.sequence() < started.sequence()
                    && frame.sessionId().equals(metadata.path("sessionId").asText())
                    && frame.targetId().equals(metadata.path("targetId").asText())
                    && frame.observationId().equals(metadata.path("observationId").asText())
                    && frame.windowGeneration() == metadata.path("windowGeneration").asLong(-1)
                    && frame.observedAtMillis() <= started.timestamp().toEpochMilli()
                    && trustedObservation(sourceEvents, frame)).findFirst().orElse(null);
            if (before == null) continue;
            RunEventEnvelope frozen = null;
            boolean revised = false;
            for (RunEventEnvelope event : sourceEvents) {
                if (!contractEvent(event)) continue;
                if (event.sequence() < started.sequence()) frozen = event;
                else revised = true;
            }
            if (frozen == null || revised) continue;
            try {
                var capabilities = TrustedCapabilityRegistry.builtins();
                TaskContractV3 source = frozen.schemaVersion() == 3
                        ? json.treeToValue(frozen.payload(), TaskContractV3.class) : null;
                if (source != null && (!source.applicable() || !source.reliable()
                        || !source.desktopObservationSubjectsValid()
                        || source.criteria().stream().anyMatch(criterion -> !capabilities.supports(criterion)))) continue;
                TaskContractV2 contract = source == null
                        ? json.treeToValue(frozen.payload(), TaskContractV2.class)
                        : TaskResultEvaluator.desktopContract(source, capabilities);
                if (contract == null || !contract.applicable() || !contract.reliable()) continue;
                for (var after : frames) {
                    if (after.sequence() <= action.sequence() || !after.sessionId().equals(before.sessionId())
                            || !after.targetId().equals(before.targetId())
                            || after.windowGeneration() != before.windowGeneration()
                            || after.observationId().equals(before.observationId())
                            || after.capturedAtMillis() <= observedAt(action)
                            || !trustedObservation(sourceEvents, after)) continue;
                    var prefix = sourceEvents.stream().filter(event -> event.sequence() <= after.sequence()).toList();
                    for (var candidate : TaskResultEvaluator.verifiedBusinessCheckpointEvidence(contract, prefix)) {
                        var proof = candidate.proof();
                        if (!proof.invocationId().equals(invocation)
                                || !proof.sessionId().equals(before.sessionId())
                                || !proof.targetId().equals(before.targetId())
                                || !proof.actionObservationId().equals(before.observationId())
                                || !proof.evidenceObservationId().equals(after.observationId())
                                || !adjacentCriteria(source, candidate.clickCriterionId(), candidate.viewCriterionId())
                                || !precedingCriteria(source, contract, candidate.clickCriterionId(),
                                        started.sequence(), sourceEvents, capabilities)) continue;
                        var effect = new EffectReconciliationV1(invocation, proof.sessionId(), proof.targetId(),
                                proof.actionObservationId(), proof.evidenceObservationId());
                        var checkpoint = new EffectCheckpointV1(effect, frozen.sequence(), candidate.clickCriterionId(),
                                candidate.viewCriterionId(), candidate.requiredSubject(), candidate.observationEvidenceRef());
                        if (!result.contains(checkpoint)) result.add(checkpoint);
                    }
                }
            } catch (Exception invalid) {
                // Invalid, partial or historical journals never gain new business-effect authority.
            }
        }
        return List.copyOf(result);
    }

    public static boolean verifies(RunId sourceRunId, List<RunEventEnvelope> sourceEvents,
            EffectCheckpointV1 checkpoint, ObjectMapper json) {
        return checkpoint != null && candidates(sourceRunId, sourceEvents, json).contains(checkpoint);
    }

    private static boolean adjacentCriteria(TaskContractV3 source, String clickId, String viewId) {
        if (source == null) return true; // The V2 shared verifier already checks its original adjacency.
        for (int index = 0; index + 1 < source.criteria().size(); index++) {
            var click = source.criteria().get(index);
            var view = source.criteria().get(index + 1);
            if (click.id().equals(clickId) && view.id().equals(viewId))
                return click.capabilityId().equals("desktop.click")
                        && view.capabilityId().equals("desktop.observe");
        }
        return false;
    }

    private static boolean precedingCriteria(TaskContractV3 source, TaskContractV2 desktop, String clickId,
            long beforeSequence, List<RunEventEnvelope> events, TrustedCapabilityRegistry capabilities) {
        var preceding = events.stream().filter(event -> event.sequence() < beforeSequence).toList();
        if (source != null) {
            for (int index = 0; index < source.criteria().size(); index++) {
                if (!source.criteria().get(index).id().equals(clickId)) continue;
                if (index == 0) return true;
                var prefix = new TaskContractV3(3, source.originalRequest(), source.criteria().subList(0, index),
                        true, true, source.source(), source.reasonCodes(), source.unresolvedInputs(),
                        source.desktopObservationPolicy(), source.intentStatus());
                return TaskResultEvaluator.evaluateV3(prefix, preceding, "", capabilities).outcome()
                        == TaskOutcome.VERIFIED_COMPLETE;
            }
        } else for (int index = 0; index < desktop.criteria().size(); index++) {
            if (!desktop.criteria().get(index).id().equals(clickId)) continue;
            if (index == 0) return true;
            var prefix = new TaskContractV2(2, desktop.originalRequest(), desktop.target(),
                    desktop.criteria().subList(0, index), true, true, desktop.source());
            return TaskResultEvaluator.evaluateV2(prefix, preceding, "").outcome() == TaskOutcome.VERIFIED_COMPLETE;
        }
        return false;
    }

    private static boolean actionTriple(RunEventEnvelope started, RunEventEnvelope completed, RunEventEnvelope action) {
        if (started == null || completed == null || started.sequence() >= completed.sequence()
                || completed.sequence() >= action.sequence() || started.timestamp().isAfter(completed.timestamp())
                || completed.timestamp().isAfter(action.timestamp())) return false;
        JsonNode input = started.payload(), output = completed.payload(), receipt = action.payload();
        JsonNode arguments = input.path("arguments"), raw = output.path("output"), metadata = receipt.path("metadata");
        long generation = arguments.path("generation").isIntegralNumber() ? arguments.path("generation").asLong(-1) : -1;
        if (!CLICK.equals(input.path("tool").asText()) || !CLICK.equals(output.path("tool").asText())
                || !input.path("trustedDesktopTool").isBoolean() || !input.path("trustedDesktopTool").booleanValue()
                || !input.path("idempotent").isBoolean() || input.path("idempotent").booleanValue()
                || !input.path("effectPolicy").asText().equals("OBSERVATION_GATED")
                || !input.path("resourceKey").asText().equals("desktop:" + metadata.path("targetId").asText())
                || !output.path("status").asText().equals("SUCCEEDED")
                || !receipt.path("evidenceRef").asText().equals("core.tool.completed:" + action.runId() + ":" + receipt.path("invocationId").asText())
                || !raw.isObject() || !raw.path("protocol").asText().equals("computer-use")
                || !raw.path("schemaVersion").isIntegralNumber() || raw.path("schemaVersion").asInt() != 1
                || !raw.path("kind").asText().equals("desktop.action") || !CLICK.equals(raw.path("tool").asText())
                || !raw.path("actionKind").asText().equals("CLICK")
                || !List.of("ACCEPTED", "VERIFIED").contains(raw.path("status").asText())
                || !raw.path("status").asText().equals(metadata.path("desktopStatus").asText())
                || !raw.path("admission").asText().equals("SUCCEEDED") || !raw.path("delivery").asText().equals("SENT")
                || !raw.path("effect").asText().equals("UNKNOWN")
                || !raw.path("dispatchAttempted").isBoolean() || !raw.path("dispatchAttempted").booleanValue()
                || !arguments.path("sessionId").asText().equals(metadata.path("sessionId").asText())
                || !arguments.path("observationId").asText().equals(metadata.path("observationId").asText())
                || !raw.path("sessionId").asText().equals(metadata.path("sessionId").asText())
                || !raw.path("targetId").asText().equals(metadata.path("targetId").asText())
                || !raw.path("observationId").asText().equals(metadata.path("observationId").asText())
                || generation < 1 || !raw.path("windowGeneration").isIntegralNumber()
                || raw.path("windowGeneration").asLong(-1) != generation
                || metadata.path("windowGeneration").asLong(-1) != generation) return false;
        try { return observedAt(action) >= started.timestamp().toEpochMilli()
                && observedAt(action) <= completed.timestamp().toEpochMilli(); }
        catch (RuntimeException invalid) { return false; }
    }

    private static boolean acceptedClick(JsonNode payload) {
        JsonNode metadata = payload.path("metadata");
        return CLICK.equals(payload.path("tool").asText()) && payload.path("operation").asText().equals("click")
                && payload.path("status").asText().equals("ACCEPTED")
                && metadata.path("delivery").asText().equals("SENT") && metadata.path("effect").asText().equals("UNKNOWN")
                && metadata.path("dispatchAttempted").asText().equals("true");
    }

    private static boolean trustedObservation(List<RunEventEnvelope> events, DesktopObservationBaseline.Frame frame) {
        var started = unique(events, "core.tool.started", 1, frame.invocationId());
        var completed = unique(events, "core.tool.completed", 2, frame.invocationId());
        var receipt = unique(events, "core.tool.receipt", 1, frame.invocationId());
        return started != null && OBSERVE.equals(started.payload().path("tool").asText())
                && started.payload().path("trustedDesktopTool").isBoolean()
                && started.payload().path("trustedDesktopTool").booleanValue()
                && completed != null && receipt != null
                && completed.payload().path("output").path("activeView").path("label").isTextual()
                && completed.payload().path("output").path("activeView").path("label").asText().strip()
                        .equals(receipt.payload().path("subject").asText());
    }

    private static RunEventEnvelope unique(List<RunEventEnvelope> events, String type, int schema, String invocation) {
        var matches = events.stream().filter(event -> event.type().equals(type)
                && event.payload().path("invocationId").asText().equals(invocation)).toList();
        return matches.size() == 1 && host(matches.getFirst(), type, schema) ? matches.getFirst() : null;
    }

    private static boolean ownOrderedJournal(RunId sourceRunId, List<RunEventEnvelope> events) {
        if (sourceRunId == null || events == null || events.isEmpty()) return false;
        long previous = 0;
        for (var event : events) {
            if (event == null || !event.runId().equals(sourceRunId.value()) || event.sequence() <= previous) return false;
            previous = event.sequence();
        }
        return true;
    }

    private static boolean contractEvent(RunEventEnvelope event) {
        return (event.type().equals("core.task.contract") || event.type().equals("core.task.contract_revised"))
                && (event.schemaVersion() == 2 || event.schemaVersion() == 3) && event.producer().equals("framework.core");
    }

    private static boolean host(RunEventEnvelope event, String type, int schema) {
        return event.type().equals(type) && event.schemaVersion() == schema && event.producer().equals("framework.core");
    }

    private static long observedAt(RunEventEnvelope event) {
        return Instant.parse(event.payload().path("observedAt").asText()).toEpochMilli();
    }
}
