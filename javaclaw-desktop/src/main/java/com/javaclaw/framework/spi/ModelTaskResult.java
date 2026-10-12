package com.javaclaw.framework.spi;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Map;

public record ModelTaskResult(
        JsonNode output,
        String model,
        long inputTokens,
        long outputTokens,
        boolean cacheHit,
        Map<String, String> metadata) {
    public ModelTaskResult {
        output = output.deepCopy();
        metadata = Map.copyOf(metadata == null ? Map.of() : metadata);
    }
    @Override public JsonNode output() { return output.deepCopy(); }
}
