package com.javaclaw.framework.core;

import com.javaclaw.framework.spi.CancellableTask;
import com.javaclaw.framework.spi.CancellableTaskExecutor;
import com.javaclaw.framework.spi.CancellationToken;
import com.javaclaw.framework.spi.ReadOnlyTaskTimeoutException;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
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
     * 仅供宿主已确认的只读工作：失败可先逻辑收尾，物理任务仍由 executor 管理。
     * 成功仍等待真实终止；绝不能用于会发送输入或产生外部副作用的任务。
     */
    public static <T> CompletableFuture<T> submitReadOnly(
            CancellableTaskExecutor executor, String name, Duration timeout,
            CancellationToken cancellation, Callable<T> action) {
        Objects.requireNonNull(executor, "executor");
        CancellableTask<T> task = executor.submit(name, timeout, cancellation, action);
        ReadOnlyFuture<T> result = new ReadOnlyFuture<>(task);
        // 独立保险不依赖 executor 的逻辑 completion 或物理 termination 回调。
        CompletableFuture<Void> deadline = new CompletableFuture<>();
        deadline.orTimeout(Math.max(1, timeout.toNanos()), TimeUnit.NANOSECONDS)
                .whenComplete((ignored, failure) -> {
                    if (failure != null) Thread.startVirtualThread(() -> result.fail(
                            new ReadOnlyTaskTimeoutException("只读任务超时: " + name + " (" + timeout + ")")));
                });
        CancellationToken.CancellationRegistration registration = cancellation.onCancel(() ->
                result.fail(new com.javaclaw.framework.spi.RunCancelledException()));
        result.whenComplete((ignored, failure) -> {
            deadline.complete(null);
            registration.close();
        });
        task.completion().whenComplete((value, failure) -> {
            if (failure != null) result.fail(unwrap(failure));
            else task.termination().whenComplete((ignored, terminationFailure) -> {
                if (terminationFailure != null) result.fail(unwrap(terminationFailure));
                else result.complete(value);
            });
        });
        return result;
    }

    private static final class ReadOnlyFuture<T> extends CompletableFuture<T> {
        private final CancellableTask<T> task;
        private final AtomicBoolean cancelRequested = new AtomicBoolean();
        ReadOnlyFuture(CancellableTask<T> task) { this.task = task; }
        void fail(Throwable failure) {
            if (completeExceptionally(failure)) cancelCarrier();
        }
        @Override public boolean cancel(boolean mayInterruptIfRunning) {
            if (!super.cancel(false)) return false;
            cancelCarrier();
            return true;
        }
        private void cancelCarrier() {
            if (cancelRequested.compareAndSet(false, true)) {
                // cancel 实现或其完成回调也可能阻塞，不能再次绑住调用方。
                Thread.startVirtualThread(() -> task.cancel());
            }
        }
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
