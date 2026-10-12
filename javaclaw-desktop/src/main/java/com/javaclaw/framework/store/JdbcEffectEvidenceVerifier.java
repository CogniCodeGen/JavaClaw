package com.javaclaw.framework.store;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.javaclaw.framework.api.*;
import com.javaclaw.framework.core.BusinessEffectCheckpointVerifier;
import com.javaclaw.framework.core.InteractionStageVerifier;
import com.javaclaw.framework.core.TaskResultEvaluator;
import com.javaclaw.framework.core.TrustedCapabilityRegistry;
import com.javaclaw.framework.spi.EffectCheckpointV1;
import com.javaclaw.framework.spi.EffectReconciliationV1;

import java.time.Instant;
import java.util.List;
import java.util.Set;

/** 核验锁内读取的原始效果证据；不写数据库、不改变状态，也不信任调用方完成声明。 */
final class JdbcEffectEvidenceVerifier {
    private final ObjectMapper json;
    private final TaskResultEvaluator evaluator;

    JdbcEffectEvidenceVerifier(ObjectMapper json) {
        this.json = java.util.Objects.requireNonNull(json, "json");
        evaluator = new TaskResultEvaluator(json);
    }

    static boolean sourceTerminatedBeforeRecovery(List<RunEventEnvelope> events,
            Instant recoveryCreatedAt) {
        return events.stream().filter(event -> event.schemaVersion() == 1
                        && event.producer().equals("framework.core")
                        && Set.of("core.run.completed", "core.run.failed", "core.run.cancelled")
                                .contains(event.type()))
                .anyMatch(event -> !event.timestamp().isAfter(recoveryCreatedAt));
    }

    boolean strictSourceActionProof(List<RunEventEnvelope> events, EffectReconciliationV1 proof) {
        try {
            TaskContractV3 source = TaskResultEvaluator.latestContractV3(events, json).orElse(null);
            TaskContractV2 contract = source == null
                    ? TaskResultEvaluator.latestContractV2(events, json).orElse(null)
                    : TaskResultEvaluator.desktopContract(source, TrustedCapabilityRegistry.builtins());
            if (contract == null || !contract.applicable() || !contract.reliable()) return false;
            if (evaluator.verifiedActionEvidence(contract, events).stream()
                    .anyMatch(candidate -> matchesEffect(candidate, proof))) return true;
            return evaluator.verifiedCheckpointEvidence(contract, events).stream()
                    .filter(candidate -> matchesEffect(candidate.proof(), proof))
                    .anyMatch(candidate -> events.stream().anyMatch(event ->
                            event.type().equals("core.task.checkpoint_verified") && event.schemaVersion() == 1
                                    && event.producer().equals("framework.core")
                                    && checkpointMatches(event.payload(), new EffectCheckpointV1(proof,
                                            event.payload().path("contractSequence").asLong(),
                                            candidate.clickCriterionId(), candidate.viewCriterionId(),
                                            candidate.requiredSubject(), candidate.observationEvidenceRef()))));
        } catch (RuntimeException invalid) {
            return false;
        }
    }

    private static boolean matchesEffect(TaskResultEvaluator.VerifiedActionEvidence candidate,
            EffectReconciliationV1 proof) {
        return candidate.invocationId().equals(proof.actionInvocationId())
                && candidate.sessionId().equals(proof.sessionId())
                && candidate.targetId().equals(proof.targetId())
                && candidate.actionObservationId().equals(proof.actionObservationId())
                && candidate.evidenceObservationId().equals(proof.evidenceObservationId());
    }

    boolean validCheckpointEvidence(List<RunEventEnvelope> events,
                                            EffectCheckpointV1 checkpoint) {
        EffectReconciliationV1 effect = checkpoint.effect();
        RunEventEnvelope started = null;
        RunEventEnvelope action = null;
        RunEventEnvelope observed = null;
        RunEventEnvelope frozen = null;
        for (RunEventEnvelope event : events) {
            if (event.type().equals("core.tool.started")
                    && event.producer().equals("framework.core")
                    && event.payload().path("invocationId").asText("")
                            .equals(effect.actionInvocationId())) started = event;
            if (event.type().equals("core.tool.receipt")
                    && event.producer().equals("framework.core")) {
                if (event.payload().path("invocationId").asText("")
                        .equals(effect.actionInvocationId())) action = event;
                if (event.payload().path("evidenceRef").asText("")
                        .equals(checkpoint.observationEvidenceRef())
                        && event.payload().path("metadata").path("observationId")
                                .asText("").equals(effect.evidenceObservationId())) observed = event;
            }
        }
        if (started == null || action == null || observed == null
                || started.sequence() >= action.sequence()
                || action.sequence() >= observed.sequence()
                || !isObservationGated(started.payload())
                || !started.payload().path("tool").asText("").equals("desktop_session_click")
                || !started.payload().path("resourceKey").asText("")
                        .equals("desktop:" + effect.targetId())) return false;
        for (RunEventEnvelope event : events) {
            if (event.sequence() >= started.sequence()) break;
            if ((event.type().equals("core.task.contract")
                    || event.type().equals("core.task.contract_revised"))
                    && (event.schemaVersion() == 2 || event.schemaVersion() == 3)
                    && event.producer().equals("framework.core")) frozen = event;
        }
        if (frozen == null || frozen.sequence() != checkpoint.contractSequence()) return false;
        long startedSequence = started.sequence();
        if (events.stream().anyMatch(event -> event.sequence() > startedSequence
                && (event.type().equals("core.task.contract")
                        || event.type().equals("core.task.contract_revised"))
                && (event.schemaVersion() == 2 || event.schemaVersion() == 3)
                && event.producer().equals("framework.core"))) return false;
        try {
            TaskContractV2 contract;
            if (frozen.schemaVersion() == 3) {
                TaskContractV3 source = json.treeToValue(frozen.payload(), TaskContractV3.class);
                TrustedCapabilityRegistry capabilities = TrustedCapabilityRegistry.builtins();
                if (!source.applicable() || !source.reliable()
                        || source.criteria().stream().anyMatch(c -> !capabilities.supports(c))) return false;
                int clickIndex = -1;
                for (int index = 0; index < source.criteria().size(); index++) {
                    if (source.criteria().get(index).id().equals(checkpoint.clickCriterionId())) {
                        clickIndex = index;
                        break;
                    }
                }
                if (clickIndex < 0) return false;
                if (clickIndex > 0) {
                    TaskContractV3 prefix = new TaskContractV3(3, source.originalRequest(),
                            source.criteria().subList(0, clickIndex), true, true, source.source(),
                            source.reasonCodes(), source.unresolvedInputs(), source.desktopObservationPolicy());
                    long actionSequence = action.sequence();
                    List<RunEventEnvelope> beforeAction = events.stream()
                            .filter(event -> event.sequence() < actionSequence).toList();
                    if (evaluator.evaluateV3(prefix, beforeAction, "", capabilities)
                            .outcome() != TaskOutcome.VERIFIED_COMPLETE) return false;
                }
                contract = TaskResultEvaluator.desktopContract(source, capabilities);
            } else {
                contract = json.treeToValue(frozen.payload(), TaskContractV2.class);
            }
            if (!contract.applicable() || !contract.reliable()) return false;
            long observedSequence = observed.sequence();
            List<RunEventEnvelope> prefix = events.stream()
                    .filter(event -> event.sequence() <= observedSequence).toList();
            return evaluator.verifiedCheckpointEvidence(contract, prefix).stream()
                    .anyMatch(candidate -> candidate.proof().invocationId()
                                    .equals(effect.actionInvocationId())
                            && candidate.proof().sessionId().equals(effect.sessionId())
                            && candidate.proof().targetId().equals(effect.targetId())
                            && candidate.proof().actionObservationId()
                                    .equals(effect.actionObservationId())
                            && candidate.proof().evidenceObservationId()
                                    .equals(effect.evidenceObservationId())
                            && candidate.clickCriterionId().equals(checkpoint.clickCriterionId())
                            && candidate.viewCriterionId().equals(checkpoint.viewCriterionId())
                            && candidate.requiredSubject().equals(checkpoint.requiredSubject())
                            && candidate.observationEvidenceRef()
                                    .equals(checkpoint.observationEvidenceRef()));
        } catch (com.fasterxml.jackson.core.JsonProcessingException invalid) {
            return false;
        }
    }

    static boolean checkpointMatches(JsonNode payload, EffectCheckpointV1 checkpoint) {
        EffectReconciliationV1 proof = checkpoint.effect();
        return payload.path("outcome").asText("").equals("SATISFIED")
                && payload.path("sessionId").asText("").equals(proof.sessionId())
                && payload.path("targetId").asText("").equals(proof.targetId())
                && payload.path("actionObservationId").asText("")
                        .equals(proof.actionObservationId())
                && payload.path("evidenceObservationId").asText("")
                        .equals(proof.evidenceObservationId())
                && payload.path("contractSequence").asLong(-1) == checkpoint.contractSequence()
                && payload.path("clickCriterionId").asText("")
                        .equals(checkpoint.clickCriterionId())
                && payload.path("viewCriterionId").asText("")
                        .equals(checkpoint.viewCriterionId())
                && payload.path("requiredSubject").asText("")
                        .equals(checkpoint.requiredSubject())
                && payload.path("observationEvidenceRef").asText("")
                        .equals(checkpoint.observationEvidenceRef());
    }

    static void rejectVerifierOnlyEvent(String type) {
        if (type.equals("core.effect.reconciled")
                || type.equals("core.task.checkpoint_verified")
                || type.equals(BusinessEffectCheckpointVerifier.EVENT_TYPE)
                || type.equals(InteractionStageVerifier.EVENT_TYPE))
            throw new IllegalArgumentException("effect verification requires verifier proof");
    }

    boolean validReconciliationEvidence(List<RunEventEnvelope> events,
                                                        EffectReconciliationV1 proof) {
        RunEventEnvelope started = null;
        RunEventEnvelope action = null;
        RunEventEnvelope observed = null;
        RunEventEnvelope checkpoint = null;
        RunEventEnvelope verifiedOutcome = null;
        for (RunEventEnvelope event : events) {
            JsonNode payload = event.payload();
            if (event.type().equals("core.task.outcome")
                    && (event.schemaVersion() == 2 || event.schemaVersion() == 3)
                    && event.producer().equals("framework.core")
                    && payload.path("outcome").asText("").equals("VERIFIED_COMPLETE"))
                verifiedOutcome = event;
            if (event.type().equals("core.task.checkpoint_verified")
                    && event.schemaVersion() == 1
                    && event.producer().equals("framework.core")
                    && payload.path("actionInvocationId").asText("")
                            .equals(proof.actionInvocationId())) checkpoint = event;
            if (event.type().equals("core.effect.reconciled")
                    && event.producer().equals("framework.core")
                    && payload.path("actionInvocationId").asText("").equals(proof.actionInvocationId()))
                return false;
            if (!event.producer().equals("framework.core") || event.schemaVersion() != 1) continue;
            if (event.type().equals("core.tool.started")
                    && payload.path("invocationId").asText("").equals(proof.actionInvocationId()))
                started = event;
            if (!event.type().equals("core.tool.receipt")) continue;
            if (payload.path("invocationId").asText("").equals(proof.actionInvocationId()))
                action = event;
            if (payload.path("operation").asText("").equals("observe")
                    && payload.path("status").asText("").equals("OBSERVED")
                    && payload.path("metadata").path("observationId").asText("")
                            .equals(proof.evidenceObservationId())) observed = event;
        }
        if (started == null || action == null || observed == null
                || started.sequence() >= action.sequence()
                || action.sequence() >= observed.sequence()
                || !isObservationGated(started.payload())
                || !started.payload().path("tool").asText("")
                        .equals("desktop_session_click")
                || !started.payload().path("resourceKey").asText("")
                        .equals("desktop:" + proof.targetId())) return false;
        RunEventEnvelope actionFrame = null;
        for (RunEventEnvelope event : events) {
            if (event.sequence() >= started.sequence()
                    || !event.type().equals("core.tool.receipt")
                    || event.schemaVersion() != 1
                    || !event.producer().equals("framework.core")) continue;
            JsonNode payload = event.payload();
            String operation = payload.path("operation").asText("");
            if ((operation.equals("observe") || operation.equals("open")
                    || java.util.Set.of("click", "type", "key", "scroll").contains(operation))
                    && payload.path("metadata").path("targetId").asText("")
                            .equals(proof.targetId())
                    && payload.path("metadata").path("sessionId").asText("")
                            .equals(proof.sessionId())) actionFrame = event;
        }
        if (actionFrame == null
                || !actionFrame.payload().path("operation").asText("").equals("observe")
                || !actionFrame.payload().path("status").asText("").equals("OBSERVED")
                || !actionFrame.payload().path("tool").asText("")
                        .equals("desktop_session_observe")
                || !actionFrame.payload().path("target").asText("")
                        .equalsIgnoreCase(action.payload().path("target").asText(""))
                || !actionFrame.payload().path("metadata").path("observationId")
                        .asText("").equals(proof.actionObservationId()))
            return false;
        JsonNode actionPayload = action.payload();
        JsonNode actionMeta = actionPayload.path("metadata");
        JsonNode observedMeta = observed.payload().path("metadata");
        JsonNode actionFrameMeta = actionFrame.payload().path("metadata");
        String observedRef = observed.payload().path("evidenceRef").asText("");
        boolean terminalProof = verifiedOutcome != null
                && verifiedOutcome.payload().path("evidenceRefs").isArray()
                && java.util.stream.StreamSupport.stream(
                        verifiedOutcome.payload().path("evidenceRefs").spliterator(), false)
                        .anyMatch(ref -> ref.isTextual() && ref.asText().equals(observedRef));
        if (terminalProof && verifiedOutcome.schemaVersion() == 3) {
            RunEventEnvelope outcome = verifiedOutcome;
            List<RunEventEnvelope> prior = events.stream()
                    .filter(event -> event.sequence() < outcome.sequence()).toList();
            TaskContractV3 contract = TaskResultEvaluator.latestContractV3(prior, json).orElse(null);
            terminalProof = contract != null
                    && evaluator.evaluateV3(contract, prior, "",
                            TrustedCapabilityRegistry.builtins()).outcome()
                            == TaskOutcome.VERIFIED_COMPLETE;
        }
        JsonNode checkpointPayload = checkpoint == null ? null : checkpoint.payload();
        boolean localProof = checkpointPayload != null
                && checkpoint.sequence() > observed.sequence()
                && checkpointPayload.path("outcome").asText("").equals("SATISFIED")
                && checkpointPayload.path("sessionId").asText("").equals(proof.sessionId())
                && checkpointPayload.path("targetId").asText("").equals(proof.targetId())
                && checkpointPayload.path("actionObservationId").asText("")
                        .equals(proof.actionObservationId())
                && checkpointPayload.path("evidenceObservationId").asText("")
                        .equals(proof.evidenceObservationId())
                && checkpointPayload.path("observationEvidenceRef").asText("")
                        .equals(observedRef)
                && checkpointPayload.path("contractSequence").asLong(-1) > 0
                && !checkpointPayload.path("clickCriterionId").asText("").isBlank()
                && !checkpointPayload.path("viewCriterionId").asText("").isBlank()
                && !checkpointPayload.path("requiredSubject").asText("").isBlank();
        if (observedRef.isBlank() || !(terminalProof || localProof))
            return false;
        if (!actionPayload.path("status").asText("").equals("UNKNOWN")
                || !actionPayload.path("operation").asText("").equals("click")
                || !actionMeta.path("delivery").asText("").equals("MAYBE_SENT")
                || !actionMeta.path("targetId").asText("").equals(proof.targetId())
                || !actionMeta.path("sessionId").asText("").equals(proof.sessionId())
                || !actionMeta.path("observationId").asText("").equals(proof.actionObservationId())
                || !actionFrameMeta.path("targetId").asText("").equals(proof.targetId())
                || !actionFrameMeta.path("sessionId").asText("").equals(proof.sessionId())
                || !actionFrameMeta.path("windowGeneration").asText("")
                        .equals(actionMeta.path("windowGeneration").asText(""))
                || actionFrameMeta.path("contentRevision").asLong(-1) < 0
                || actionFrameMeta.path("capturedAtMillis").asLong(0) <= 0
                || !observedMeta.path("targetId").asText("").equals(proof.targetId())
                || !observedMeta.path("sessionId").asText("").equals(proof.sessionId())
                || !observed.payload().path("tool").asText("")
                        .equals("desktop_session_observe")
                || !observed.payload().path("target").asText("")
                        .equalsIgnoreCase(actionPayload.path("target").asText(""))
                || proof.actionObservationId().equals(proof.evidenceObservationId())
                || observedMeta.path("viewEvidence").asText("").isBlank()
                || observedMeta.path("capturedAtMillis").asLong(0) <= 0
                || !observedMeta.path("windowGeneration").asText("")
                        .equals(actionMeta.path("windowGeneration").asText(""))) return false;
        try {
            java.util.UUID.fromString(proof.actionObservationId());
            java.util.UUID.fromString(proof.evidenceObservationId());
            if (actionFrameMeta.path("capturedAtMillis").asLong(0)
                    > java.time.Instant.parse(actionPayload.path("observedAt")
                            .asText("")).toEpochMilli()) return false;
            if (java.time.Instant.parse(observed.payload().path("observedAt").asText(""))
                    .toEpochMilli() <= java.time.Instant.parse(actionPayload.path("observedAt")
                            .asText("")).toEpochMilli()) return false;
        } catch (RuntimeException invalidTime) { return false; }
        for (RunEventEnvelope between : events) {
            if (between.sequence() <= action.sequence() || between.sequence() >= observed.sequence()
                    || !between.type().equals("core.tool.receipt")) continue;
            JsonNode payload = between.payload();
            if (java.util.Set.of("click", "type", "key", "scroll")
                    .contains(payload.path("operation").asText(""))
                    && payload.path("metadata").path("targetId").asText("")
                            .equals(proof.targetId())) return false;
        }
        return true;
    }

    static boolean isObservationGated(JsonNode started) {
        String policy = started.path("effectPolicy").asText("");
        if (!policy.isBlank()) return policy.equals("OBSERVATION_GATED");
        return java.util.Set.of("desktop_session_click", "desktop_session_type",
                "desktop_session_key", "desktop_session_scroll")
                .contains(started.path("tool").asText(""));
    }

}
