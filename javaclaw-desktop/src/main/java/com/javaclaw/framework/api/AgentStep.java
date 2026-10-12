package com.javaclaw.framework.api;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;

/** Read-only projection of the durable step event stream; output retains raw and model views. */
public record AgentStep(StepId id, String threadId, RunId turnId, Kind kind, State state,
                        String causationStepId, JsonNode input, JsonNode output,
                        JsonNode usage, String error, Instant startedAt, Instant endedAt,
                        long startSequence, long lastSequence) {
    public enum Kind { MODEL, MODEL_TASK, TOOL, ORCHESTRATION }
    public enum State { RUNNING, COMPLETED, FAILED }
    public AgentStep {
        input = copy(input); output = copy(output); usage = copy(usage);
    }
    @Override public JsonNode input() { return copy(input); }
    @Override public JsonNode output() { return copy(output); }
    @Override public JsonNode usage() { return copy(usage); }
    private static JsonNode copy(JsonNode node) { return node == null ? null : node.deepCopy(); }
}
