package com.javaclaw.framework.api;

import java.util.Objects;
import java.util.UUID;

/** Stable identity of one AgentEngine execution. */
public record RunId(String value) {
    public RunId {
        value = Objects.requireNonNull(value, "value").trim();
        if (value.isEmpty()) {
            throw new IllegalArgumentException("run id must not be blank");
        }
    }

    public static RunId random() {
        return new RunId(UUID.randomUUID().toString());
    }

    @Override
    public String toString() {
        return value;
    }
}
