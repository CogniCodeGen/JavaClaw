package com.javaclaw.framework.spi;

import com.javaclaw.framework.api.RunId;

import java.time.Instant;

public record ToolExecutionContext(
        RunId runId,
        String invocationId,
        CancellationToken cancellation,
        Instant deadline,
        String causationStepId,
        boolean internalContextRead) {
    public ToolExecutionContext(RunId runId, String invocationId, CancellationToken cancellation, Instant deadline) {
        this(runId, invocationId, cancellation, deadline, null, false);
    }

    public ToolExecutionContext(RunId runId, String invocationId, CancellationToken cancellation,
                                Instant deadline, String causationStepId) {
        this(runId, invocationId, cancellation, deadline, causationStepId, false);
    }
}
