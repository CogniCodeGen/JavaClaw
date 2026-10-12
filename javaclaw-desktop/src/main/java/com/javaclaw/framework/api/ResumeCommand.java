package com.javaclaw.framework.api;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Objects;

/** Versionable external input used to resume a paused or waiting run. */
public record ResumeCommand(String type, JsonNode payload) {
    public ResumeCommand {
        type = Objects.requireNonNull(type, "type").trim();
        payload = Objects.requireNonNull(payload, "payload").deepCopy();
        if (type.isEmpty()) {
            throw new IllegalArgumentException("resume command type must not be blank");
        }
    }
    @Override public JsonNode payload() { return payload.deepCopy(); }
}
