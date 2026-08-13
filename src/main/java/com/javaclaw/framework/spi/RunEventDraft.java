package com.javaclaw.framework.spi;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Objects;

/** Unsequenced event committed transactionally by RunStore. */
public record RunEventDraft(
        String type,
        int schemaVersion,
        String producer,
        String correlationId,
        String causationId,
        JsonNode payload) {
    public RunEventDraft {
        type = Objects.requireNonNull(type, "type");
        producer = Objects.requireNonNull(producer, "producer");
        payload = Objects.requireNonNull(payload, "payload").deepCopy();
        if (schemaVersion < 1) throw new IllegalArgumentException("schemaVersion must be positive");
    }
    @Override public JsonNode payload() { return payload.deepCopy(); }
}
