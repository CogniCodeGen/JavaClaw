package com.javaclaw.framework.core;

import com.javaclaw.framework.spi.CancellableTask;
import com.javaclaw.framework.spi.CancellableTaskExecutor;
import com.javaclaw.framework.spi.CancellationToken;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CancellableTaskStagesTest {

    @Test
    void callerCancellationSettlesOnlyAfterCarrierTermination() {
        PendingTask<String> task = new PendingTask<>();
        CancellableTaskExecutor executor = new FixedExecutor(task);

        CompletableFuture<String> result = CancellableTaskStages.submit(
                executor, "pending", Duration.ofSeconds(1), () -> false, () -> "unused");

        assertTrue(result.cancel(true));
        assertTrue(task.cancelRequested.get());
        assertFalse(result.isDone());

        task.completion.completeExceptionally(new java.util.concurrent.CancellationException());
        assertFalse(result.isDone());

        task.termination.complete(null);
        assertTrue(result.isCancelled());
    }

    private static final class FixedExecutor implements CancellableTaskExecutor {
        private final CancellableTask<?> task;

        private FixedExecutor(CancellableTask<?> task) {
            this.task = task;
        }

        @Override public void execute(Runnable command) { command.run(); }

        @SuppressWarnings("unchecked")
        @Override
        public <T> CancellableTask<T> submit(
                String name, Duration timeout, CancellationToken cancellation, Callable<T> action) {
            return (CancellableTask<T>) task;
        }
    }

    private static final class PendingTask<T> implements CancellableTask<T> {
        private final CompletableFuture<T> completion = new CompletableFuture<>();
        private final CompletableFuture<Void> termination = new CompletableFuture<>();
        private final AtomicBoolean cancelRequested = new AtomicBoolean();

        @Override public java.util.concurrent.CompletionStage<T> completion() {
            return completion;
        }

        @Override public java.util.concurrent.CompletionStage<Void> termination() {
            return termination;
        }

        @Override public boolean cancel() {
            return cancelRequested.compareAndSet(false, true);
        }
    }
}
