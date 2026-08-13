package com.javaclaw.framework.spi;

import com.javaclaw.framework.api.RunId;

import java.time.Duration;
import java.util.Objects;

public record RetryContext(
        RunId runId,
        String operation,
        int attempt,
        Throwable failure,
        Duration remaining) {
    public RetryContext {
        runId = Objects.requireNonNull(runId, "runId");
        operation = Objects.requireNonNull(operation, "operation");
        failure = Objects.requireNonNull(failure, "failure");
        remaining = Objects.requireNonNull(remaining, "remaining");
        if (attempt < 1) throw new IllegalArgumentException("attempt must be positive");
    }
}
