package com.javaclaw.framework.spi;

import com.fasterxml.jackson.databind.JsonNode;

@FunctionalInterface
public interface ModelPolicy {
    /** May replace, but never instantiate, the model selected by the published definition. */
    String select(String currentPolicyRef, ModelTier tier, JsonNode context);
}
