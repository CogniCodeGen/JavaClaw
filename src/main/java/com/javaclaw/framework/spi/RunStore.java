package com.javaclaw.framework.spi;

import com.fasterxml.jackson.databind.JsonNode;
import com.javaclaw.framework.api.RunEventEnvelope;
import com.javaclaw.framework.api.RunId;
import com.javaclaw.framework.api.RunRequest;
import com.javaclaw.framework.api.RunState;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/** Durable run/event/outbox boundary. Each mutation is one database transaction. */
public interface RunStore {
    CreateRunResult create(RunId id, RunRequest request, String executionPlanId, RunEventDraft createdEvent);

    Optional<StoredRun> find(RunId id);

    Optional<StoredRun> findByIdempotencyKey(String workspaceId, String idempotencyKey);

    default Optional<StoredRun> findByIdempotencyKey(com.javaclaw.framework.api.RunScope scope, String key) {
        return findByIdempotencyKey(scope.workspaceId(), key).filter(run -> run.request().scope().equals(scope));
    }
    default RunRequest prepare(RunRequest request) { return request; }
    /** Public reads must honor thread tombstones; internal cancellation and recovery use raw find. */
    default boolean readable(com.javaclaw.framework.api.RunScope scope) { return true; }
    default boolean claim(RunId id) { return true; }
    default Optional<RunId> release(RunId id) { return Optional.empty(); }
    /** Process-start reconciliation only; never steal a terminal owner's live cleanup lease. */
    default void recoverClaims() { }

    List<StoredRun> nonTerminalRuns();

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
}
