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

    List<StoredRun> nonTerminalRuns();

    List<RunEventEnvelope> eventsAfter(RunId id, long afterSequence);

    Optional<RunEventEnvelope> append(
            RunId id,
            Set<RunState> expectedStates,
            RunState nextState,
            RunEventDraft event,
            JsonNode output,
            String error);

    /**
     * Appends a fact without changing Run state. Unlike a transition, this may be used for an
     * explicitly background audit event after the owning Run reached a terminal state.
     */
    Optional<RunEventEnvelope> appendEvent(RunId id, RunEventDraft event);
}
