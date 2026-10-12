package com.javaclaw.plugin.runner;

import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/** SDK 后台工作独立于 gRPC 计数，排空后仅允许已有工作继续派生完成任务。 */
final class TrackedPluginExecutor extends AbstractExecutorService {
    private final ExecutorService delegate = Executors.newVirtualThreadPerTaskExecutor();
    private final AtomicInteger active = new AtomicInteger();
    private final ThreadLocal<Boolean> managed = ThreadLocal.withInitial(() -> false);
    private final BooleanSupplier inRpc;
    private final Supplier<ClassLoader> loader;
    private final Runnable changed;
    private final Supplier<GrpcHostClient.TaskToken> capture;
    private final Object admission = new Object();
    private boolean draining;

    TrackedPluginExecutor(BooleanSupplier inRpc, Supplier<ClassLoader> loader, Runnable changed,
                          Supplier<GrpcHostClient.TaskToken> capture) {
        this.inRpc = inRpc; this.loader = loader; this.changed = changed; this.capture = capture;
    }
    int activeCount() { return active.get(); }
    void beginDrain() { synchronized (admission) { draining = true; } }

    @Override public void execute(Runnable command) {
        synchronized (admission) {
            if (delegate.isShutdown() || draining && !managed.get() && !inRpc.getAsBoolean())
                throw new RejectedExecutionException("插件已停止接收后台工作");
            if (active.get() >= 128) throw new RejectedExecutionException("插件后台并发额度耗尽");
            GrpcHostClient.TaskToken token = capture.get();
            active.incrementAndGet();
            try {
                delegate.execute(() -> {
                    Thread thread = Thread.currentThread();
                    ClassLoader previous = thread.getContextClassLoader();
                    thread.setContextClassLoader(loader.get()); managed.set(true);
                    try (token) { if (token != null) token.enter(); command.run(); }
                    catch (Throwable failure) {
                        if (command instanceof TrackedFutureTask<?> future) future.fail(failure);
                        else if (failure instanceof RuntimeException runtime) throw runtime;
                        else throw new CompletionException(failure);
                    }
                    finally {
                        managed.remove(); thread.setContextClassLoader(previous);
                        active.decrementAndGet(); changed.run();
                    }
                });
            } catch (RuntimeException failure) {
                if (token != null) token.close();
                active.decrementAndGet(); changed.run(); throw failure;
            }
        }
    }
    @Override protected <T> RunnableFuture<T> newTaskFor(Callable<T> callable) { return new TrackedFutureTask<>(callable); }
    @Override protected <T> RunnableFuture<T> newTaskFor(Runnable runnable, T value) {
        return new TrackedFutureTask<>(Executors.callable(runnable, value));
    }
    private static final class TrackedFutureTask<T> extends FutureTask<T> {
        private TrackedFutureTask(Callable<T> callable) { super(callable); }
        private void fail(Throwable failure) { setException(failure); }
    }
    @Override public void shutdown() { synchronized (admission) { delegate.shutdown(); } }
    @Override public List<Runnable> shutdownNow() { synchronized (admission) { return delegate.shutdownNow(); } }
    @Override public boolean isShutdown() { return delegate.isShutdown(); }
    @Override public boolean isTerminated() { return delegate.isTerminated(); }
    @Override public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
        return delegate.awaitTermination(timeout, unit);
    }
}
