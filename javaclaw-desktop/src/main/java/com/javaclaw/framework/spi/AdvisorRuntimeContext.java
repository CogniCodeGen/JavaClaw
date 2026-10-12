package com.javaclaw.framework.spi;

import com.javaclaw.framework.api.RunId;
import com.javaclaw.framework.api.RunRequest;

import java.util.Objects;

/** Run-owned services exposed while an exact extension creates a Spring AI Advisor. */
public record AdvisorRuntimeContext(
        RunId runId,
        RunRequest request,
        ExtensionStateView state,
        ModelTaskGateway modelTasks,
        CancellationToken cancellation) {

    public AdvisorRuntimeContext {
        runId = Objects.requireNonNull(runId, "runId");
        request = Objects.requireNonNull(request, "request");
        state = Objects.requireNonNull(state, "state");
        modelTasks = Objects.requireNonNull(modelTasks, "modelTasks");
        cancellation = Objects.requireNonNull(cancellation, "cancellation");
    }
}
