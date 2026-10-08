package com.javaclaw.framework.core;

import com.fasterxml.jackson.databind.JsonNode;

/** Host control transfer: the tool is reserved, but its child result has not arrived. */
public final class InteractionWaitRequiredException extends RuntimeException {
    private final JsonNode context;

    public InteractionWaitRequiredException(JsonNode context) {
        super("INTERACTION_CHILD_PENDING");
        this.context = context.deepCopy();
    }

    public JsonNode context() { return context.deepCopy(); }
}
