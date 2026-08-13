package com.javaclaw.framework.api;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Objects;

public record ToolCallEvent(String type, int schemaVersion, String producer, JsonNode payload) {
    public ToolCallEvent {
        type = Objects.requireNonNull(type, "type");
        producer = Objects.requireNonNull(producer, "producer");
        payload = Objects.requireNonNull(payload, "payload").deepCopy();
        if (schemaVersion < 1) throw new IllegalArgumentException("schemaVersion must be positive");
    }
    @Override public JsonNode payload() { return payload.deepCopy(); }
}
