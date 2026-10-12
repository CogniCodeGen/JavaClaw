package com.javaclaw.framework.spi;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Objects;

/** Host continuation after a normal result/receipt, never evidence that the requested task is done. */
public record ToolUserInputCheckpoint(Phase phase, JsonNode context, String reason) {
    public enum Phase { NOT_SENT, RESULT_ESTABLISHED }
    public ToolUserInputCheckpoint {
        phase = Objects.requireNonNull(phase);
        context = Objects.requireNonNull(context).deepCopy();
        reason = reason == null ? "" : reason;
    }
    @Override public JsonNode context() { return context.deepCopy(); }
}
