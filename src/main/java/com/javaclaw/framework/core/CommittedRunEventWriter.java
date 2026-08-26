package com.javaclaw.framework.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.javaclaw.framework.api.RunEventEnvelope;
import com.javaclaw.framework.api.RunId;
import com.javaclaw.framework.api.RunState;
import com.javaclaw.framework.spi.RunEventDraft;
import com.javaclaw.framework.spi.RunStore;

import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Publishes only envelopes successfully committed by RunStore. */
public final class CommittedRunEventWriter {
    private final RunStore runs;
    private final RunEventRelay relay;

    public CommittedRunEventWriter(RunStore runs, RunEventRelay relay) {
        this.runs = Objects.requireNonNull(runs, "runs");
        this.relay = Objects.requireNonNull(relay, "relay");
    }

    public Optional<RunEventEnvelope> transition(
            RunId id, Set<RunState> expectedStates, RunState nextState,
            RunEventDraft event, JsonNode output, String error) {
        Optional<RunEventEnvelope> committed = runs.append(
                id, expectedStates, nextState, event, output, error);
        committed.ifPresent(relay::publish);
        return committed;
    }

    public Optional<RunEventEnvelope> append(RunId id, RunEventDraft event) {
        Optional<RunEventEnvelope> committed = runs.appendEvent(id, event);
        committed.ifPresent(relay::publish);
        return committed;
    }
}
