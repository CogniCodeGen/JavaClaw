package com.javaclaw.app;

import com.javaclaw.platform.fx.FxDispatcher;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicBoolean;

/** Orders idempotent JavaFX cleanup before process-level infrastructure cleanup. */
final class ApplicationShutdownCoordinator {
    private final FxDispatcher fx;
    private final AtomicBoolean uiScheduled = new AtomicBoolean(false);
    private final AtomicBoolean backendStarted = new AtomicBoolean(false);
    private final CompletableFuture<Void> uiClosed = new CompletableFuture<>();
    private final CompletableFuture<Void> backendClosed = new CompletableFuture<>();

    ApplicationShutdownCoordinator(FxDispatcher fx) {
        this.fx = Objects.requireNonNull(fx, "fx");
    }

    /** Schedules UI cleanup exactly once and returns the shared completion signal. */
    CompletableFuture<Void> closeUi(Runnable cleanup) {
        Objects.requireNonNull(cleanup, "cleanup");
        if (uiScheduled.compareAndSet(false, true)) {
            try {
                fx.dispatch(() -> {
                    try {
                        cleanup.run();
                        uiClosed.complete(null);
                    } catch (Throwable failure) {
                        uiClosed.completeExceptionally(failure);
                    }
                });
            } catch (Throwable schedulingFailure) {
                uiClosed.completeExceptionally(schedulingFailure);
            }
        }
        return uiClosed;
    }

    /** Closes backend resources exactly once, and never before UI cleanup completed successfully. */
    void closeBackend(Runnable cleanup) {
        Objects.requireNonNull(cleanup, "cleanup");
        if (!uiClosed.isDone()) {
            throw new IllegalStateException("JavaFX 视图尚未完成清理");
        }
        try {
            uiClosed.join();
        } catch (CompletionException failure) {
            throw new IllegalStateException("JavaFX 视图清理失败", failure.getCause());
        }
        runBackend(cleanup);
    }

    /**
     * During process exit JavaFX may already have stopped accepting work. Release the backend
     * even when UI cleanup could not complete. A concurrent normal close still owns the same
     * completion signal and cannot be bypassed by a second cleanup.
     */
    void closeBackendForProcessExit(Runnable cleanup) {
        Objects.requireNonNull(cleanup, "cleanup");
        runBackend(cleanup);
    }

    private void runBackend(Runnable cleanup) {
        if (backendStarted.compareAndSet(false, true)) {
            try {
                cleanup.run();
                backendClosed.complete(null);
            } catch (RuntimeException | Error failure) {
                backendClosed.completeExceptionally(failure);
                throw failure;
            }
        } else {
            // In particular, a shutdown hook must not return while another thread is still
            // closing the database: the JVM would otherwise terminate that cleanup mid-write.
            backendClosed.join();
        }
    }
}
