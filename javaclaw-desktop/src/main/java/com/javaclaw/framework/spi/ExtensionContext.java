package com.javaclaw.framework.spi;

import java.time.Clock;
import java.util.Objects;
import java.util.concurrent.Executor;

/** Lifecycle context. Executor tasks must honor the supplied cancellation boundary. */
public record ExtensionContext(
        Clock clock,
        Executor executor,
        ModelTaskGateway modelTasks,
        ExtensionStateStore extensionState) {

    public ExtensionContext {
        clock = Objects.requireNonNull(clock, "clock");
        executor = Objects.requireNonNull(executor, "executor");
        modelTasks = Objects.requireNonNull(modelTasks, "modelTasks");
        extensionState = Objects.requireNonNull(extensionState, "extensionState");
    }

    public ExtensionContext(Clock clock, Executor executor, ModelTaskGateway modelTasks) {
        this(clock, executor, modelTasks, ExtensionStateStore.disabled());
    }
}
