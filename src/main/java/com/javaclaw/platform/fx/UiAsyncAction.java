package com.javaclaw.platform.fx;

import com.javaclaw.platform.execution.ManagedTask;
import com.javaclaw.platform.execution.ManagedTaskExecutor;
import com.javaclaw.platform.execution.TaskHandle;
import com.javaclaw.platform.execution.TaskSpec;
import javafx.beans.property.ReadOnlyBooleanProperty;
import javafx.beans.property.ReadOnlyBooleanWrapper;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.ReadOnlyObjectWrapper;

import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * JavaFX 异步动作的统一生命周期。
 *
 * <p>每次执行会取消上一次，统一维护 busy/failure，并把成功或失败回调切回 FX 线程。
 * 使用单调世代号丢弃取消、页面关闭或后续执行产生的迟到结果。实例不持有 Service/Repository，
 * 页面关闭时必须调用 {@link #close()}。</p>
 */
public final class UiAsyncAction<T> implements AutoCloseable {

    private final ManagedTaskExecutor executor;
    private final FxDispatcher fx;
    private final ReadOnlyBooleanWrapper busy = new ReadOnlyBooleanWrapper(false);
    private final ReadOnlyObjectWrapper<Throwable> failure = new ReadOnlyObjectWrapper<>();
    private final AtomicLong generation = new AtomicLong();
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private volatile TaskHandle<T> current;

    public UiAsyncAction(ManagedTaskExecutor executor, FxDispatcher fx) {
        this.executor = java.util.Objects.requireNonNull(executor, "executor");
        this.fx = java.util.Objects.requireNonNull(fx, "fx");
    }

    public ReadOnlyBooleanProperty busyProperty() {
        return busy.getReadOnlyProperty();
    }

    public ReadOnlyObjectProperty<Throwable> failureProperty() {
        return failure.getReadOnlyProperty();
    }

    public void execute(
            TaskSpec spec,
            ManagedTask<T> task,
            Consumer<? super T> onSuccess,
            Consumer<? super Throwable> onFailure) {
        if (closed.get()) {
            throw new IllegalStateException("UiAsyncAction 已关闭");
        }
        java.util.Objects.requireNonNull(onSuccess, "onSuccess");
        java.util.Objects.requireNonNull(onFailure, "onFailure");
        long run = generation.incrementAndGet();
        TaskHandle<T> previous = current;
        if (previous != null) {
            previous.cancel();
        }
        fx.dispatch(() -> {
            if (isCurrent(run)) {
                failure.set(null);
                busy.set(true);
            }
        });

        TaskHandle<T> submitted;
        try {
            submitted = executor.submit(spec, task);
            current = submitted;
        } catch (Throwable submissionFailure) {
            dispatchFailure(run, submissionFailure, onFailure);
            return;
        }
        submitted.completion().whenComplete((result, thrown) -> fx.dispatch(() -> {
            if (!isCurrent(run) || current != submitted) {
                return;
            }
            current = null;
            busy.set(false);
            if (thrown == null) {
                onSuccess.accept(result);
                return;
            }
            Throwable cause = unwrap(thrown);
            if (!(cause instanceof CancellationException)) {
                failure.set(cause);
                onFailure.accept(cause);
            }
        }));
    }

    public void cancel() {
        generation.incrementAndGet();
        TaskHandle<T> handle = current;
        current = null;
        if (handle != null) {
            handle.cancel();
        }
        fx.dispatch(() -> busy.set(false));
    }

    private void dispatchFailure(
            long run, Throwable thrown, Consumer<? super Throwable> onFailure) {
        fx.dispatch(() -> {
            if (!isCurrent(run)) {
                return;
            }
            busy.set(false);
            failure.set(thrown);
            onFailure.accept(thrown);
        });
    }

    private boolean isCurrent(long run) {
        return !closed.get() && generation.get() == run;
    }

    private static Throwable unwrap(Throwable failure) {
        Throwable current = failure;
        while ((current instanceof CompletionException || current instanceof ExecutionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            cancel();
        }
    }
}
