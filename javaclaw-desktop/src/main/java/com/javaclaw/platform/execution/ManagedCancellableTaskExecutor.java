package com.javaclaw.platform.execution;

import com.javaclaw.framework.spi.CancellableTask;
import com.javaclaw.framework.spi.CancellableTaskExecutor;
import com.javaclaw.framework.spi.CancellationToken;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Callable;

/** Adapter exposing {@link ManagedTaskExecutor}'s cancellation and termination semantics. */
public final class ManagedCancellableTaskExecutor implements CancellableTaskExecutor {
    private final ManagedTaskExecutor tasks;

    public ManagedCancellableTaskExecutor(ManagedTaskExecutor tasks) {
        this.tasks = Objects.requireNonNull(tasks, "tasks");
    }

    @Override
    public void execute(Runnable command) {
        submit("agent-framework", Duration.ZERO, () -> false, () -> {
            command.run();
            return null;
        });
    }

    @Override
    public <T> CancellableTask<T> submit(
            String name, Duration timeout, CancellationToken cancellation, Callable<T> task) {
        Objects.requireNonNull(cancellation, "cancellation");
        Objects.requireNonNull(task, "task");
        TaskHandle<T> handle = tasks.submit(
                TaskSpec.io(name).withTimeout(timeout), context -> {
                    cancellation.throwIfCancelled();
                    context.cancellation().throwIfCancellationRequested();
                    return task.call();
                });
        CancellationToken.CancellationRegistration registration =
                cancellation.onCancel(handle::cancel);
        handle.termination().whenComplete((ignored, failure) -> registration.close());
        return new CancellableTask<>() {
            @Override public java.util.concurrent.CompletionStage<T> completion() {
                return handle.completion();
            }
            @Override public java.util.concurrent.CompletionStage<Void> termination() {
                return handle.termination();
            }
            @Override public boolean cancel() { return handle.cancel(); }
        };
    }
}
