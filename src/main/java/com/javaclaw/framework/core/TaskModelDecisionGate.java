package com.javaclaw.framework.core;

import com.javaclaw.framework.api.ModelDecisionV1;
import com.javaclaw.framework.api.RunEventEnvelope;
import com.javaclaw.framework.api.TaskOutcome;
import com.javaclaw.framework.api.TaskResult;
import com.javaclaw.framework.api.TaskStopReason;

import java.util.List;
import java.util.Optional;

/** Applies a separately submitted model decision to host-verified evidence. */
final class TaskModelDecisionGate {
    private TaskModelDecisionGate() { }

    /** A model proposal can gate completion, but never establish it without receipts. */
    static TaskResult gateWithModelDecision(TaskResult evidenceResult,
            List<RunEventEnvelope> ownEvents) {
        Optional<ModelDecisionV1.Decision> decision = latestModelDecision(ownEvents);
        if (decision.orElse(null) == ModelDecisionV1.Decision.CLAIM_DONE) {
            return evidenceResult.outcome() == TaskOutcome.NOT_APPLICABLE
                    ? TaskResult.delivered() : evidenceResult;
        }
        if (decision.orElse(null) == ModelDecisionV1.Decision.BLOCKED) {
            return new TaskResult(TaskOutcome.BLOCKED, evidenceResult.unmetCriteria(),
                    TaskStopReason.MODEL_BLOCKED.name(), evidenceResult.evidenceRefs(),
                    evidenceResult.satisfiedCriteria());
        }
        if (evidenceResult.outcome() == TaskOutcome.NOT_APPLICABLE) {
            return TaskResult.unverified(decision.orElse(null)
                    == ModelDecisionV1.Decision.NEEDS_INPUT
                    ? "MODEL_NEEDS_INPUT" : "MODEL_DELIVERY_NOT_CLAIMED");
        }
        if (evidenceResult.outcome() != TaskOutcome.VERIFIED_COMPLETE) return evidenceResult;
        return new TaskResult(TaskOutcome.UNVERIFIED, evidenceResult.unmetCriteria(),
                "MODEL_COMPLETION_NOT_CLAIMED", evidenceResult.evidenceRefs(),
                evidenceResult.satisfiedCriteria());
    }

    static Optional<ModelDecisionV1.Decision> latestModelDecision(
            List<RunEventEnvelope> ownEvents) {
        long lastBoundary = decisionBoundary(ownEvents);
        RunEventEnvelope submitted = ownEvents.stream()
                .filter(event -> event.sequence() > lastBoundary
                        && event.type().equals("core.harness.decision_submitted")
                        && event.schemaVersion() == 1
                        && event.producer().equals("framework.springai"))
                .max(java.util.Comparator.comparingLong(RunEventEnvelope::sequence)).orElse(null);
        if (submitted == null || ownEvents.stream().anyMatch(event ->
                event.sequence() > submitted.sequence()
                        && event.type().equals("core.tool.started")
                        && event.producer().equals("framework.core"))
                || ownEvents.stream().anyMatch(event ->
                event.sequence() > submitted.sequence()
                        && event.type().equals("core.step.started")
                        && event.producer().equals("framework.core")
                        && event.payload().path("kind").asText("").equals("MODEL"))) {
            return Optional.empty();
        }
        String modelStepId = submitted.payload().path("modelStepId").asText("");
        String invocationId = submitted.payload().path("invocationId").asText("");
        if (modelStepId.isBlank() || invocationId.isBlank()) return Optional.empty();
        long freshModelStart = lastBoundary == 0 ? 0 : ownEvents.stream()
                .filter(event -> event.sequence() > lastBoundary
                        && event.sequence() < submitted.sequence()
                        && event.type().equals("core.step.started")
                        && event.producer().equals("framework.core")
                        && event.payload().path("kind").asText("").equals("MODEL")
                        && modelStepId.equals(event.payload().path("stepId").asText("")))
                .mapToLong(RunEventEnvelope::sequence).min().orElse(Long.MAX_VALUE);
        boolean modelCompleted = ownEvents.stream().anyMatch(event ->
                event.sequence() > freshModelStart && event.sequence() < submitted.sequence()
                        && event.type().equals("core.step.completed")
                        && event.producer().equals("framework.core")
                        && modelStepId.equals(event.payload().path("stepId").asText("")));
        List<RunEventEnvelope> controls = ownEvents.stream()
                .filter(event -> event.sequence() > lastBoundary && event.sequence() < submitted.sequence()
                        && event.type().equals("core.step.started")
                        && event.producer().equals("framework.core")
                        && event.payload().path("kind").asText("").equals("ORCHESTRATION")
                        && event.payload().path("input").path("phase").asText("")
                                .equals("harness.decision")
                        && modelStepId.equals(event.payload().path("input")
                                .path("modelStepId").asText(""))
                        && invocationId.equals(event.payload().path("input")
                                .path("invocationId").asText("")))
                .toList();
        if (!modelCompleted || controls.size() != 1 || ownEvents.stream().noneMatch(event ->
                event.sequence() > submitted.sequence()
                        && event.type().equals("core.step.completed")
                        && event.producer().equals("framework.core")
                        && controls.getFirst().payload().path("stepId").asText("")
                                .equals(event.payload().path("stepId").asText("")))) {
            return Optional.empty();
        }
        try {
            ModelDecisionV1 value = ModelDecisionV1.fromJson(
                    submitted.payload().path("value"));
            return Optional.of(value.decision());
        } catch (RuntimeException invalid) {
            return Optional.empty();
        }
    }

    /** New human input or revised acceptance conditions require a fresh model decision. */
    static long decisionBoundary(List<RunEventEnvelope> ownEvents) {
        return ownEvents.stream()
                .filter(event -> ((event.type().equals("core.task.repair_requested")
                                || event.type().equals("core.harness.protocol_repair_requested"))
                        && event.producer().equals("framework.springai"))
                        || (event.producer().equals("framework.core")
                                && ((event.type().equals("core.run.resumed") && event.schemaVersion() == 1
                                                && newHumanInput(event))
                                        || (event.type().equals("core.task.contract_revised")
                                                && event.schemaVersion() == 3))))
                .mapToLong(RunEventEnvelope::sequence).max().orElse(0);
    }

    private static boolean newHumanInput(RunEventEnvelope event) {
        var payload = event.payload();
        return "user.input".equals(payload.path("commandType").asText())
                && payload.path("command").path("text").isTextual()
                && !payload.path("command").path("text").asText().isBlank();
    }

}
