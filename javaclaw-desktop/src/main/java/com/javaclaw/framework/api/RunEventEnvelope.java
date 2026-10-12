package com.javaclaw.framework.api;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.Objects;

/** Versioned, open event envelope shared by UI, SSE, A2A and AG-UI adapters. */
public record RunEventEnvelope(
        String runId,
        long sequence,
        Instant timestamp,
        String type,
        int schemaVersion,
        String producer,
        String correlationId,
        String causationId,
        JsonNode payload) {

    public RunEventEnvelope {
        runId = required(runId, "runId");
        timestamp = Objects.requireNonNull(timestamp, "timestamp");
        type = required(type, "type");
        producer = required(producer, "producer");
        payload = Objects.requireNonNull(payload, "payload").deepCopy();
        if (sequence < 1 || schemaVersion < 1) {
            throw new IllegalArgumentException("sequence and schemaVersion must be positive");
        }
    }

    public boolean terminal() {
        return type.equals("core.run.completed")
                || type.equals("core.run.failed")
                || type.equals("core.run.cancelled");
    }

    @Override public JsonNode payload() { return payload.deepCopy(); }

    private static String required(String value, String field) {
        value = Objects.requireNonNull(value, field).trim();
        if (value.isEmpty()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }
}
