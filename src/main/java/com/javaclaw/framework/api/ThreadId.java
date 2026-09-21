package com.javaclaw.framework.api;

import java.util.Objects;
import java.util.UUID;

/** A thread identifier, always resolved together with its workspace and user. */
public record ThreadId(String value) {
    public ThreadId {
        value = Objects.requireNonNull(value, "value").strip();
        if (value.isEmpty()) throw new IllegalArgumentException("thread id must not be blank");
    }
    public static ThreadId random() { return new ThreadId(UUID.randomUUID().toString()); }
    @Override public String toString() { return value; }
}
