package com.javaclaw.framework.spi;

import com.fasterxml.jackson.databind.JsonNode;
import com.javaclaw.framework.api.RunEventEnvelope;
import com.javaclaw.framework.api.RunId;
import com.javaclaw.framework.api.RunRequest;
import com.javaclaw.framework.api.RunState;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

/** Durable run/event/outbox boundary. Each mutation is one database transaction. */
public interface RunStore {
    CreateRunResult create(RunId id, RunRequest request, String executionPlanId, RunEventDraft createdEvent);

    Optional<StoredRun> find(RunId id);

    Optional<StoredRun> findByIdempotencyKey(String workspaceId, String idempotencyKey);

    default Optional<StoredRun> findByIdempotencyKey(com.javaclaw.framework.api.RunScope scope, String key) {
        return findByIdempotencyKey(scope.workspaceId(), key).filter(run -> run.request().scope().equals(scope));
    }
    default RunRequest prepare(RunRequest request) { return request; }
    /**
     * Hold the owning Run and thread state fence through evidence acceptance or native
     * rediscovery reservation. Reads and durable writes in work must share this atomic
     * fence; implementations without it fail closed rather than allowing duplicate dispatch.
     */
    default <T> T withRunAcceptanceLock(RunId id, Supplier<T> work) {
        throw new UnsupportedOperationException("RunStore has no atomic task acceptance fence");
    }
    /** Public reads must honor thread tombstones; internal cancellation and recovery use raw find. */
    default boolean readable(com.javaclaw.framework.api.RunScope scope) { return true; }
    default boolean claim(RunId id) { return true; }
    default Optional<RunId> release(RunId id) { return Optional.empty(); }
    /** Process-start reconciliation only; never steal a terminal owner's live cleanup lease. */
    default void recoverClaims() { }

    List<StoredRun> nonTerminalRuns();

    /**
     * Complete history for one exact workspace/user/session scope, including terminal Runs.
     * New turns inherit unresolved effects from cancelled, failed and completed turns;
     * returning only active Runs would silently remove that safety boundary on restart.
     * Implementations must override this with an authoritative scope-restricted query.
     */
    default List<StoredRun> scopeRuns(com.javaclaw.framework.api.RunScope scope) {
        throw new UnsupportedOperationException("RunStore has no complete scope effect history");
    }

    /** Includes terminal children: their physical charges still belong to the parent budget. */
    default List<StoredRun> childRuns(RunId parentId) {
        return nonTerminalRuns().stream().filter(run -> parentId.equals(run.request().linkage().parentRunId())).toList();
    }

    List<RunEventEnvelope> eventsAfter(RunId id, long afterSequence);

    /** Completes an already started step once, preserving the owner state even after cancellation. */
    default Optional<RunEventEnvelope> settleStep(RunId id, RunEventDraft event) { return Optional.empty(); }

    Optional<RunEventEnvelope> append(
            RunId id,
            Set<RunState> expectedStates,
            RunState nextState,
            RunEventDraft event,
            JsonNode output,
            String error);

    /** Persist an ordered group of run events as one mutation. JDBC overrides this atomically. */
    default Optional<List<RunEventEnvelope>> appendBatch(
            RunId id, Set<RunState> expectedStates, RunState nextState,
            List<RunEventDraft> events) {
        List<RunEventEnvelope> appended = new java.util.ArrayList<>();
        for (RunEventDraft event : events) {
            Optional<RunEventEnvelope> value = append(id, expectedStates, nextState,
                    event, null, null);
            if (value.isEmpty()) return Optional.empty();
            appended.add(value.get());
        }
        return Optional.of(List.copyOf(appended));
    }

    /** Only the trusted V2 verifier may supply a matched action-specific postcondition. */
    default Optional<RunEventEnvelope> reconcileEffect(
            RunId id, EffectReconciliationV1 proof) {
        return Optional.empty();
    }

    /** A newer same-scope Run may repair a missed verifier write using the terminal source's own proof. */
    default Optional<RunEventEnvelope> reconcileTerminalEffect(
            RunId recoveryRunId, RunId sourceRunId, EffectReconciliationV1 proof) {
        return Optional.empty();
    }

    /** Commit a verifier-derived local V2 checkpoint before releasing an uncertain input. */
    default Optional<RunEventEnvelope> verifyEffectCheckpoint(
            RunId id, EffectCheckpointV1 checkpoint) {
        return Optional.empty();
    }

    /** Independently verifies an accepted desktop click's predeclared business result on its source Run. */
    default Optional<RunEventEnvelope> verifyBusinessEffectCheckpoint(
            RunId sourceRunId, EffectCheckpointV1 checkpoint) {
        return Optional.empty();
    }

    /** Verify one original-contract stage, without changing delivery receipts or run state. */
    default Optional<RunEventEnvelope> verifyInteractionStage(RunId sourceRunId, InteractionStageProofV1 proof) {
        return Optional.empty();
    }
}
