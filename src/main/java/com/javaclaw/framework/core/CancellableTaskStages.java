package com.javaclaw.framework.core;

import com.javaclaw.framework.spi.CancellableTask;
import com.javaclaw.framework.spi.CancellableTaskExecutor;
import com.javaclaw.framework.spi.CancellationToken;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicBoolean;

/** Converts the cancellable-task port into stages that settle only after real termination. */
public final class CancellableTaskStages {
    private CancellableTaskStages() { }

    public static <T> CompletableFuture<T> submit(
            CancellableTaskExecutor executor, String name, Duration timeout,
            CancellationToken cancellation, Callable<T> action) {
        Objects.requireNonNull(executor, "executor");
        return afterTermination(executor.submit(name, timeout, cancellation, action));
    }

    private static <T> CompletableFuture<T> afterTermination(CancellableTask<T> task) {
        TerminationAwareFuture<T> result = new TerminationAwareFuture<>(task);
        task.completion().whenComplete((value, failure) ->
                task.termination().whenComplete((ignored, terminationFailure) -> {
                    if (result.cancellationRequested()) result.finishCancelled();
                    else if (failure != null) result.completeExceptionally(unwrap(failure));
                    else if (terminationFailure != null) {
                        result.completeExceptionally(unwrap(terminationFailure));
                    } else result.complete(value);
                }));
        return result;
    }

    /**
     * A caller cancellation is forwarded immediately, but the public stage is not
     * completed until the carrier has really terminated. This prevents run-scoped
     * resources from being closed while ignored interrupts can still execute code.
     */
    private static final class TerminationAwareFuture<T> extends CompletableFuture<T> {
        private final CancellableTask<T> task;
        private final AtomicBoolean cancellationRequested = new AtomicBoolean();

        private TerminationAwareFuture(CancellableTask<T> task) {
            this.task = Objects.requireNonNull(task, "task");
        }

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            if (isDone()) return false;
            if (cancellationRequested.compareAndSet(false, true)) task.cancel();
            return true;
        }

        private boolean cancellationRequested() {
            return cancellationRequested.get();
        }

        private void finishCancelled() {
            super.cancel(false);
        }
    }

    public static Throwable unwrap(Throwable failure) {
        Throwable current = failure;
        while ((current instanceof CompletionException
                || current instanceof java.util.concurrent.ExecutionException)
                && current.getCause() != null) current = current.getCause();
        return current;
    }
}
