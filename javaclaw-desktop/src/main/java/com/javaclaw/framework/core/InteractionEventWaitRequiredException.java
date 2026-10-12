package com.javaclaw.framework.core;

import com.fasterxml.jackson.databind.JsonNode;

/** A native-event wait, persisted and resumed by the host without invoking a model. */
public final class InteractionEventWaitRequiredException extends RuntimeException {
    private final JsonNode context;
    public InteractionEventWaitRequiredException(JsonNode context) {
        super("INTERACTION_EVENT_PENDING");
        this.context = context.deepCopy();
    }
    public JsonNode context() { return context.deepCopy(); }
}
