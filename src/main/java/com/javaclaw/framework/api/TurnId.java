package com.javaclaw.framework.api;

import java.util.Objects;

/** Public turn identity; the durable Run remains the execution state machine. */
public record TurnId(String value) {
    public TurnId {
        value = Objects.requireNonNull(value, "value").strip();
        if (value.isEmpty()) throw new IllegalArgumentException("turn id must not be blank");
    }
    public static TurnId from(RunId id) { return new TurnId(id.value()); }
    public RunId runId() { return new RunId(value); }
    @Override public String toString() { return value; }
}
