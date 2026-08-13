package com.javaclaw.framework.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.javaclaw.framework.api.RunState;

import java.util.Objects;

/** A reasoning turn either completes the run or explicitly suspends it. */
public record ReasoningResult(RunState nextState, JsonNode output, String reason) {
    public ReasoningResult {
        nextState = Objects.requireNonNull(nextState, "nextState");
        output = output == null ? null : output.deepCopy();
        reason = reason == null ? "" : reason;
        if (nextState != RunState.COMPLETED
                && nextState != RunState.WAITING_INPUT
                && nextState != RunState.WAITING_APPROVAL
                && nextState != RunState.PAUSED) {
            throw new IllegalArgumentException("unsupported reasoning result state: " + nextState);
        }
    }

    public static ReasoningResult completed(JsonNode output) {
        return new ReasoningResult(RunState.COMPLETED, output, "");
    }

    public static ReasoningResult waitingForInput(JsonNode context, String reason) {
        return new ReasoningResult(RunState.WAITING_INPUT, context, reason);
    }

    public static ReasoningResult waitingForApproval(JsonNode context, String reason) {
        return new ReasoningResult(RunState.WAITING_APPROVAL, context, reason);
    }
}
