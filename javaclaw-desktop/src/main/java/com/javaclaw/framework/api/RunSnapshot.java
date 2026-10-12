package com.javaclaw.framework.api;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;

/** Durable current view of a run. */
public record RunSnapshot(
        RunId id,
        RunState state,
        String executionPlanId,
        long lastSequence,
        Instant createdAt,
        Instant updatedAt,
        JsonNode output,
        String error,
        long version) {

    public RunSnapshot {
        output = output == null ? null : output.deepCopy();
    }
    @Override public JsonNode output() { return output == null ? null : output.deepCopy(); }
}
