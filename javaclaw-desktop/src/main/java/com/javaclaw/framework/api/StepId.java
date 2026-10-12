package com.javaclaw.framework.api;

import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.UUID;

/** Stable identity of one atomic operation within a turn. */
public record StepId(String value) {
    public StepId {
        value = Objects.requireNonNull(value, "value").trim();
        if (value.isEmpty()) throw new IllegalArgumentException("step id must not be blank");
    }
    public static StepId random() { return new StepId(UUID.randomUUID().toString()); }
    public static StepId tool(RunId turnId, String invocationId) {
        return new StepId(UUID.nameUUIDFromBytes((turnId.value() + ":tool:" + invocationId)
                .getBytes(StandardCharsets.UTF_8)).toString());
    }
}
