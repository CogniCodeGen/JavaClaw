package com.javaclaw.framework.core;

import com.fasterxml.jackson.databind.JsonNode;

/** Stops a child before another side effect when a cross-backend business outcome is unproved. */
public final class CrossModeBusinessEffectUnverifiedException extends RuntimeException {
    private final JsonNode context;

    public CrossModeBusinessEffectUnverifiedException(JsonNode context) {
        super("CROSS_MODE_BUSINESS_UNVERIFIED");
        this.context = context.deepCopy();
    }

    public JsonNode context() { return context.deepCopy(); }
}
