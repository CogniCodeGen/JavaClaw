package com.javaclaw.framework.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.javaclaw.framework.api.RunEventEnvelope;
import com.javaclaw.framework.api.RunId;
import com.javaclaw.framework.api.TaskContractV2;
import com.javaclaw.framework.api.TaskContractV3;
import com.javaclaw.framework.spi.EffectCheckpointV1;
import com.javaclaw.framework.spi.EffectReconciliationV1;
import com.javaclaw.framework.spi.RunStore;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Persists only action-specific evidence established by the trusted V2 task verifier. */
public final class VerifiedEffectReconciler {
    private VerifiedEffectReconciler() { }

    /** Repairs a missed verifier write without revising a terminal source's contract or task result. */
    public static List<RunEventEnvelope> recoverTerminalEffects(RunStore store,
            RunId recoveryRunId, RunId sourceRunId, ObjectMapper json) {
        Objects.requireNonNull(store, "store");
        Objects.requireNonNull(json, "json");
        var recovery = store.find(recoveryRunId).orElse(null);
        var source = store.find(sourceRunId).orElse(null);
        if (recovery == null || source == null || recoveryRunId.equals(sourceRunId)
                || recovery.snapshot().state().terminal() || !source.snapshot().state().terminal()
                || !recovery.request().scope().equals(source.request().scope())
                || !store.readable(source.request().scope())) return List.of();
        List<RunEventEnvelope> durable = store.eventsAfter(sourceRunId, 0);
        List<TaskResultEvaluator.VerifiedActionEvidence> proofs;
        try {
            TaskContractV3 original = TaskResultEvaluator.latestContractV3(durable, json).orElse(null);
            TaskContractV2 contract = original == null
                    ? TaskResultEvaluator.latestContractV2(durable, json).orElse(null)
                    : TaskResultEvaluator.desktopContract(original, TrustedCapabilityRegistry.builtins());
            if (contract == null || !contract.applicable() || !contract.reliable()) return List.of();
            var candidates = new ArrayList<>(TaskResultEvaluator.verifiedActionEvidence(contract, durable));
            TaskResultEvaluator.verifiedCheckpointEvidence(contract, durable).stream()
                    .map(TaskResultEvaluator.VerifiedCheckpointEvidence::proof)
                    .filter(proof -> !candidates.contains(proof)).forEach(candidates::add);
            proofs = List.copyOf(candidates);
        } catch (RuntimeException invalid) {
            // An unreadable old contract cannot authorize releasing an input barrier.
            return List.of();
        }
        List<RunEventEnvelope> repaired = new ArrayList<>();
        for (var candidate : proofs) {
            store.reconcileTerminalEffect(recoveryRunId, sourceRunId,
                    new EffectReconciliationV1(candidate.invocationId(), candidate.sessionId(),
                            candidate.targetId(), candidate.actionObservationId(), candidate.evidenceObservationId()))
                    .ifPresent(repaired::add);
        }
        return List.copyOf(repaired);
    }

    /**
     * Check a local V2 view predicate after a new observation, while a Run is still active.
     * The checkpoint is durable before the input barrier is released. A restart between
     * those writes may safely repeat this call; the store recognizes the same checkpoint.
     */
    public static List<ReconciledCheckpoint> reconcileCheckpoints(RunStore store, RunId runId,
            RunControl control, TaskContractV2 contract, List<RunEventEnvelope> evidence) {
        return reconcileCheckpointsInternal(store, runId, control, null, contract,
                TrustedCapabilityRegistry.builtins(), evidence);
    }

    public static List<ReconciledCheckpoint> reconcileCheckpoints(RunStore store, RunId runId,
            RunControl control, TaskContractV3 sourceContract, TaskContractV2 desktopContract,
            TrustedCapabilityRegistry capabilities, List<RunEventEnvelope> evidence) {
        Objects.requireNonNull(sourceContract, "sourceContract");
        return reconcileCheckpointsInternal(store, runId, control, sourceContract,
                desktopContract, capabilities, evidence);
    }

    private static List<ReconciledCheckpoint> reconcileCheckpointsInternal(RunStore store,
            RunId runId, RunControl control, TaskContractV3 sourceContract,
            TaskContractV2 contract, TrustedCapabilityRegistry capabilities,
            List<RunEventEnvelope> evidence) {
        Objects.requireNonNull(store, "store");
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(control, "control");
        Objects.requireNonNull(contract, "contract");
        Objects.requireNonNull(evidence, "evidence");
        List<RunEventEnvelope> durable = TaskEvidenceCollector.collect(store, runId);
        if (!durable.containsAll(evidence)) return List.of();
        List<ReconciledCheckpoint> committed = new ArrayList<>();
        for (TaskResultEvaluator.VerifiedCheckpointEvidence candidate
                : TaskResultEvaluator.verifiedCheckpointEvidence(contract, durable)) {
            if (sourceContract != null && !precedingCriteriaSatisfied(sourceContract,
                    candidate, durable, capabilities)) continue;
            long contractSequence = frozenContractSequence(durable, runId, sourceContract,
                    contract, candidate.proof().invocationId());
            if (contractSequence <= 0) continue;
            var proof = candidate.proof();
            EffectReconciliationV1 effect = new EffectReconciliationV1(proof.invocationId(),
                    proof.sessionId(), proof.targetId(), proof.actionObservationId(),
                    proof.evidenceObservationId());
            EffectCheckpointV1 checkpoint = new EffectCheckpointV1(effect, contractSequence,
                    candidate.clickCriterionId(), candidate.viewCriterionId(),
                    candidate.requiredSubject(), candidate.observationEvidenceRef());
            store.verifyEffectCheckpoint(runId, checkpoint).ifPresent(verified ->
                    store.reconcileEffect(runId, effect).ifPresent(reconciled -> {
                        control.restoreEffectReconciliation(effect.actionInvocationId(),
                                "desktop:" + effect.targetId());
                        committed.add(new ReconciledCheckpoint(proof, verified, reconciled));
                    }));
        }
        return List.copyOf(committed);
    }

    private static boolean precedingCriteriaSatisfied(TaskContractV3 sourceContract,
            TaskResultEvaluator.VerifiedCheckpointEvidence candidate,
            List<RunEventEnvelope> durable, TrustedCapabilityRegistry capabilities) {
        int criterionIndex = -1;
        for (int index = 0; index < sourceContract.criteria().size(); index++) {
            if (sourceContract.criteria().get(index).id().equals(candidate.clickCriterionId())) {
                criterionIndex = index;
                break;
            }
        }
        if (criterionIndex < 0) return false;
        if (criterionIndex == 0) return true;
        int actionIndex = -1;
        for (int index = 0; index < durable.size(); index++) {
            RunEventEnvelope event = durable.get(index);
            if (event.type().equals("core.tool.receipt")
                    && event.payload().path("invocationId").asText("")
                            .equals(candidate.proof().invocationId())) {
                actionIndex = index;
                break;
            }
        }
        if (actionIndex < 0) return false;
        TaskContractV3 prefix = new TaskContractV3(3, sourceContract.originalRequest(),
                sourceContract.criteria().subList(0, criterionIndex), true, true,
                sourceContract.source(), sourceContract.reasonCodes(), sourceContract.unresolvedInputs(),
                sourceContract.desktopObservationPolicy());
        return TaskResultEvaluator.evaluateV3(prefix, durable.subList(0, actionIndex), "",
                capabilities).outcome() == com.javaclaw.framework.api.TaskOutcome.VERIFIED_COMPLETE;
    }

    private static long frozenContractSequence(List<RunEventEnvelope> events, RunId runId,
            TaskContractV3 sourceContract, TaskContractV2 contract, String invocationId) {
        long startedSequence = Long.MAX_VALUE;
        for (RunEventEnvelope event : events) {
            if (event.runId().equals(runId.value())
                    && event.type().equals("core.tool.started")
                    && event.payload().path("invocationId").asText("").equals(invocationId)) {
                startedSequence = Math.min(startedSequence, event.sequence());
            }
        }
        if (startedSequence == Long.MAX_VALUE) return -1;
        RunEventEnvelope frozen = null;
        for (RunEventEnvelope event : events) {
            if (!event.runId().equals(runId.value()) || event.sequence() >= startedSequence
                    || !(event.type().equals("core.task.contract")
                            || event.type().equals("core.task.contract_revised"))
                    || event.schemaVersion() != (sourceContract == null ? 2 : 3)
                    || !event.producer().equals("framework.core")) continue;
            if (frozen == null || event.sequence() > frozen.sequence()) frozen = event;
        }
        if (frozen == null) return -1;
        return (sourceContract == null
                ? persistedContractMatches(frozen.payload(), contract)
                : persistedContractMatchesV3(frozen.payload(), sourceContract))
                ? frozen.sequence() : -1;
    }

    private static boolean persistedContractMatchesV3(
            com.fasterxml.jackson.databind.JsonNode value, TaskContractV3 contract) {
        if (value.path("version").asInt(-1) != 3
                || !value.path("originalRequest").asText("").equals(contract.originalRequest())
                || value.path("applicable").asBoolean(!contract.applicable()) != contract.applicable()
                || value.path("reliable").asBoolean(!contract.reliable()) != contract.reliable()
                || !value.path("source").asText("").equals(contract.source())
                || !value.path("criteria").isArray()
                || value.path("criteria").size() != contract.criteria().size()) return false;
        for (int index = 0; index < contract.criteria().size(); index++) {
            var expected = contract.criteria().get(index);
            var actual = value.path("criteria").get(index);
            if (!actual.path("id").asText("").equals(expected.id())
                    || !actual.path("description").asText("").equals(expected.description())
                    || !actual.path("capabilityId").asText("").equals(expected.capabilityId())
                    || !actual.path("target").asText("").equals(expected.target())
                    || !actual.path("requiredEvidence").asText("")
                            .equals(expected.requiredEvidence().name())
                    || !actual.path("requiredSubject").asText("")
                            .equals(expected.requiredSubject())) return false;
        }
        return true;
    }

    private static boolean persistedContractMatches(com.fasterxml.jackson.databind.JsonNode value,
            TaskContractV2 contract) {
        var criteria = value.path("criteria");
        if (value.path("version").asInt(-1) != 2
                || !value.path("originalRequest").asText("").equals(contract.originalRequest())
                || !value.path("target").asText("").equals(contract.target())
                || value.path("applicable").asBoolean(!contract.applicable()) != contract.applicable()
                || value.path("reliable").asBoolean(!contract.reliable()) != contract.reliable()
                || !value.path("source").asText("").equals(contract.source())
                || !criteria.isArray() || criteria.size() != contract.criteria().size()) return false;
        for (int index = 0; index < contract.criteria().size(); index++) {
            var expected = contract.criteria().get(index);
            var actual = criteria.get(index);
            if (!actual.path("id").asText("").equals(expected.id())
                    || !actual.path("description").asText("").equals(expected.description())
                    || !actual.path("target").asText("").equals(expected.target())
                    || !actual.path("requiredOperation").asText("")
                            .equals(expected.requiredOperation())
                    || !actual.path("requiredEvidence").asText("")
                            .equals(expected.requiredEvidence())
                    || !actual.path("requiredSubject").asText("")
                            .equals(expected.requiredSubject())) return false;
        }
        return true;
    }

    public record ReconciledCheckpoint(TaskResultEvaluator.VerifiedActionEvidence proof,
            RunEventEnvelope checkpointEvent, RunEventEnvelope reconciliationEvent) { }

    /** Durable reconciliation for the terminal verifier; a resumed Run replays the event. */
    public static List<RunEventEnvelope> reconcile(RunStore store, RunId runId,
            TaskContractV2 contract, List<RunEventEnvelope> evidence) {
        return reconcileInternal(store, runId, null, contract, evidence);
    }

    public static List<RunEventEnvelope> reconcile(RunStore store, RunId runId,
            RunControl control, TaskContractV2 contract, List<RunEventEnvelope> evidence) {
        Objects.requireNonNull(control, "control");
        return reconcileInternal(store, runId, control, contract, evidence);
    }

    private static List<RunEventEnvelope> reconcileInternal(RunStore store, RunId runId,
            RunControl control, TaskContractV2 contract, List<RunEventEnvelope> evidence) {
        Objects.requireNonNull(store, "store");
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(contract, "contract");
        Objects.requireNonNull(evidence, "evidence");
        // Re-read durable evidence. A caller's transient/model-provided list never authorizes
        // release of the input barrier.
        List<RunEventEnvelope> durable = TaskEvidenceCollector.collect(store, runId);
        if (!durable.containsAll(evidence)) return List.of();
        List<RunEventEnvelope> committed = new ArrayList<>();
        for (TaskResultEvaluator.VerifiedActionEvidence candidate
                : TaskResultEvaluator.verifiedActionEvidence(contract, durable)) {
            EffectReconciliationV1 proof = new EffectReconciliationV1(
                    candidate.invocationId(), candidate.sessionId(), candidate.targetId(),
                    candidate.actionObservationId(), candidate.evidenceObservationId());
            store.reconcileEffect(runId, proof).ifPresent(event -> {
                if (control != null) control.restoreEffectReconciliation(
                        proof.actionInvocationId(), "desktop:" + proof.targetId());
                committed.add(event);
            });
        }
        return List.copyOf(committed);
    }
}
