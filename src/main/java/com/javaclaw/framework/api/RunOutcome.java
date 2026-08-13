package com.javaclaw.framework.api;

import com.fasterxml.jackson.databind.JsonNode;

/** Terminal result exposed by RunHandle. */
public record RunOutcome(RunId runId, RunState state, JsonNode output, String error) {
    public RunOutcome {
        if (!state.terminal()) {
            throw new IllegalArgumentException("run outcome must be terminal");
        }
        output = output == null ? null : output.deepCopy();
    }

    public boolean successful() {
        return state == RunState.COMPLETED;
    }
    @Override public JsonNode output() { return output == null ? null : output.deepCopy(); }
}
