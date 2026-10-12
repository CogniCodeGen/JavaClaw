package com.javaclaw.framework.core;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Objects;

/** Internal control signal used by a tool to suspend its owner Run for explicit user input. */
public final class ToolInputRequiredException extends RuntimeException {
    private final JsonNode context;

    public ToolInputRequiredException(JsonNode context, String reason) {
        super(reason == null ? "" : reason);
        this.context = Objects.requireNonNull(context, "context").deepCopy();
    }

    public JsonNode context() {
        return context.deepCopy();
    }
}
