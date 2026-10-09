package com.javaclaw.desktop.ffm;

import com.javaclaw.desktop.api.DesktopAction;
import com.javaclaw.desktop.api.DesktopActionResult;
import com.javaclaw.desktop.api.DesktopApplicationCatalog;
import com.javaclaw.desktop.api.DesktopApplicationLaunch;
import com.javaclaw.desktop.api.DesktopAvailability;
import com.javaclaw.desktop.api.DesktopElement;
import com.javaclaw.desktop.api.DesktopFrame;
import com.javaclaw.desktop.api.DesktopInputPolicy;
import com.javaclaw.desktop.nativebridge.DesktopBridge.NativeWindow;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * Keeps every native operation on one OS thread, including across Java waits inside it.
 * Virtual threads may change carriers while parked, so native thread-local scopes cannot
 * safely surround such waits on the calling thread.
 */
public final class ThreadBoundDesktopApi implements SystemDesktopApi {
    private static final DesktopExecutor SHARED = new DesktopExecutor(4, 64);
    private final SystemDesktopApi delegate;
    private final DesktopExecutor executor;

    public ThreadBoundDesktopApi(SystemDesktopApi delegate) { this(delegate, SHARED); }

    ThreadBoundDesktopApi(SystemDesktopApi delegate, DesktopExecutor executor) {
        this.delegate = Objects.requireNonNull(delegate);
        this.executor = Objects.requireNonNull(executor);
    }

    @Override public DesktopAvailability availability(boolean request, DesktopInputPolicy policy) {
        return executor.call(() -> delegate.availability(request, policy));
    }
    @Override public List<NativeWindow> windows() { return executor.call(delegate::windows); }
    @Override public DesktopApplicationCatalog applications() { return executor.call(delegate::applications); }
    @Override public DesktopApplicationLaunch launch(String application) {
        return executor.call(() -> delegate.launch(application));
    }
    @Override public Optional<Boolean> windowExists(long processId, long windowId, long processInstanceId) {
        return executor.call(() -> delegate.windowExists(processId, windowId, processInstanceId));
    }
    @Override public SystemDesktopSession open(NativeWindow window) {
        return executor.call(() -> new BoundSession(Objects.requireNonNull(delegate.open(window))));
    }

    private final class BoundSession implements SystemDesktopSession {
        private final SystemDesktopSession session;
        BoundSession(SystemDesktopSession session) { this.session = session; }
        @Override public NativeWindow current() { return executor.call(session::current); }
        @Override public Optional<DesktopFrame> capture(String targetId, int timeoutMillis) {
            return executor.call(() -> session.capture(targetId, timeoutMillis));
        }
        @Override public List<DesktopElement> elements(DesktopFrame frame) {
            return executor.call(() -> session.elements(frame));
        }
        @Override public String diagnostics() { return executor.call(session::diagnostics); }
        @Override public Optional<Boolean> targetActive() { return executor.call(session::targetActive); }
        @Override public void prepareForeground() {
            executor.call(() -> { session.prepareForeground(); return null; });
        }
        @Override public void restoreForeground() {
            executor.call(() -> { session.restoreForeground(); return null; }, true);
        }
        @Override public DesktopActionResult perform(DesktopAction action, boolean foreground) {
            return executor.call(() -> session.perform(action, foreground));
        }
        @Override public void close() {
            // The facade marks its session closed before this call; cleanup cannot be dropped.
            executor.call(() -> { session.close(); return null; }, true);
        }
    }

    /** Fixed platform workers and a bounded queue; saturation never runs native work on a caller. */
    static final class DesktopExecutor implements AutoCloseable {
        private static final AtomicInteger THREAD_IDS = new AtomicInteger();
        private static final ThreadLocal<DesktopExecutor> WORKER = new ThreadLocal<>();
        private final ThreadPoolExecutor pool;

        DesktopExecutor(int workers, int queueCapacity) {
            if (workers < 1 || queueCapacity < 1) throw new IllegalArgumentException("Invalid desktop executor bounds");
            pool = new ThreadPoolExecutor(workers, workers, 0, TimeUnit.MILLISECONDS,
                    new ArrayBlockingQueue<>(queueCapacity, true), runnable ->
                    Thread.ofPlatform().daemon().name("JavaClaw-desktop-ffm-" + THREAD_IDS.incrementAndGet())
                            .unstarted(() -> {
                                WORKER.set(this);
                                try { runnable.run(); }
                                finally { WORKER.remove(); }
                            }), new ThreadPoolExecutor.AbortPolicy());
            pool.prestartAllCoreThreads();
        }

        <T> T call(Supplier<T> operation) { return call(operation, false); }

        <T> T call(Supplier<T> operation, boolean cleanup) {
            if (WORKER.get() == this) return operation.get();
            boolean interrupted = Thread.interrupted();
            try {
                if (interrupted && !cleanup)
                    throw interruptedBeforeDispatch(new InterruptedException("Desktop caller was already interrupted"));
                Call<T> call = new Call<>(operation);
                try { pool.execute(call); }
                catch (RejectedExecutionException saturated) {
                    if (!cleanup || pool.isShutdown()) throw saturated;
                    // A close must survive queue saturation without falling back to the caller.
                    while (true) {
                        try { pool.getQueue().put(call); break; }
                        catch (InterruptedException waitInterrupted) { interrupted = true; }
                    }
                }
                while (true) {
                    try { return call.result.get(); }
                    catch (InterruptedException waitInterrupted) {
                        interrupted = true;
                        if (!cleanup && call.cancelBeforeStart()) {
                            pool.remove(call);
                            throw interruptedBeforeDispatch(waitInterrupted);
                        }
                        // Once started, wait for this one operation's real outcome. Never replay
                        // input, interrupt its native worker, or release its session concurrently.
                    } catch (ExecutionException failure) {
                        Throwable cause = failure.getCause();
                        if (cause instanceof RuntimeException runtime) throw runtime;
                        if (cause instanceof Error error) throw error;
                        throw new IllegalStateException("Desktop operation failed", cause);
                    }
                }
            } finally {
                if (interrupted) Thread.currentThread().interrupt();
            }
        }

        private static IllegalStateException interruptedBeforeDispatch(InterruptedException cause) {
            return new IllegalStateException("Desktop operation interrupted before native dispatch", cause);
        }

        int queuedOperations() { return pool.getQueue().size(); }

        /** Only private test executors are closed; the production daemon pool is process scoped. */
        @Override public void close() {
            pool.shutdown();
            boolean interrupted = Thread.interrupted();
            try {
                while (true) {
                    try {
                        if (!pool.awaitTermination(5, TimeUnit.SECONDS))
                            throw new IllegalStateException("Desktop executor still has active operations");
                        return;
                    } catch (InterruptedException waitInterrupted) { interrupted = true; }
                }
            } finally { if (interrupted) Thread.currentThread().interrupt(); }
        }
    }

    private static final class Call<T> implements Runnable {
        private static final int QUEUED = 0, STARTED = 1, CANCELLED = 2;
        private final AtomicInteger state = new AtomicInteger(QUEUED);
        private final CompletableFuture<T> result = new CompletableFuture<>();
        private final Supplier<T> operation;
        Call(Supplier<T> operation) { this.operation = operation; }
        boolean cancelBeforeStart() { return state.compareAndSet(QUEUED, CANCELLED); }
        @Override public void run() {
            if (!state.compareAndSet(QUEUED, STARTED)) return;
            try { result.complete(operation.get()); }
            catch (Throwable failure) { result.completeExceptionally(failure); }
        }
    }
}
