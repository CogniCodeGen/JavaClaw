package com.javaclaw.framework.spi;

import com.javaclaw.framework.api.RunId;

import java.time.Instant;

public record ToolExecutionContext(
        RunId runId,
        String invocationId,
        CancellationToken cancellation,
        Instant deadline,
        String causationStepId) {
    public ToolExecutionContext(RunId runId, String invocationId, CancellationToken cancellation, Instant deadline) {
        this(runId, invocationId, cancellation, deadline, null);
    }
}
