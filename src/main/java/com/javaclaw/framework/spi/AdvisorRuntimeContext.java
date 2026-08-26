package com.javaclaw.framework.spi;

import com.javaclaw.framework.api.RunId;
import com.javaclaw.framework.api.RunRequest;
import com.javaclaw.framework.core.ReasoningEventSink;

import java.util.Objects;

/** Run-owned services exposed while an exact extension creates a Spring AI Advisor. */
public record AdvisorRuntimeContext(
        RunId runId,
        RunRequest request,
        ExtensionStateView state,
        ModelTaskGateway modelTasks,
        CancellationToken cancellation,
        ReasoningEventSink events) {

    public AdvisorRuntimeContext {
        runId = Objects.requireNonNull(runId, "runId");
        request = Objects.requireNonNull(request, "request");
        state = Objects.requireNonNull(state, "state");
        modelTasks = Objects.requireNonNull(modelTasks, "modelTasks");
        cancellation = Objects.requireNonNull(cancellation, "cancellation");
        events = Objects.requireNonNull(events, "events");
    }

    /** Source-compatible constructor for extension tests and non-reasoning callers. */
    public AdvisorRuntimeContext(RunId runId, RunRequest request, ExtensionStateView state,
                                 ModelTaskGateway modelTasks, CancellationToken cancellation) {
        this(runId, request, state, modelTasks, cancellation,
                (type, schemaVersion, producer, payload) -> { });
    }
}
