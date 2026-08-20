package com.javaclaw.service.runner;

import com.javaclaw.service.api.ManagedPluginExecutor;
import com.javaclaw.service.api.Registration;

import java.time.Duration;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

final class RunnerExecutor implements ManagedPluginExecutor, AutoCloseable {
    private final ExecutorService tasks;
    private final ScheduledExecutorService timers;
    private final AtomicBoolean closed = new AtomicBoolean();

    RunnerExecutor() {
        this(Thread.currentThread().getContextClassLoader());
    }

    RunnerExecutor(ClassLoader contextClassLoader) {
        tasks = Executors.newThreadPerTaskExecutor(withContextClassLoader(
                Thread.ofVirtual().name("service-plugin-task-", 0).factory(), contextClassLoader));
        timers = Executors.newSingleThreadScheduledExecutor(withContextClassLoader(
                Thread.ofPlatform().name("service-plugin-timer-", 0).factory(), contextClassLoader));
    }

    static ThreadFactory withContextClassLoader(ThreadFactory delegate, ClassLoader contextClassLoader) {
        if (delegate == null) throw new IllegalArgumentException("thread factory is required");
        if (contextClassLoader == null) throw new IllegalArgumentException("context classloader is required");
        return task -> {
            Thread thread = delegate.newThread(task);
            thread.setContextClassLoader(contextClassLoader);
            return thread;
        };
    }

    @Override
    public CompletionStage<Void> run(String name, Runnable task) {
        return call(name, () -> { task.run(); return null; });
    }

    @Override
    public <T> CompletionStage<T> call(String name, Callable<T> task) {
        if (closed.get()) return CompletableFuture.failedFuture(
                new IllegalStateException("service plugin executor is closed"));
        CompletableFuture<T> result = new CompletableFuture<>();
        tasks.submit(() -> {
            try { result.complete(task.call()); }
            catch (Throwable failure) { result.completeExceptionally(failure); }
        });
        return result;
    }

    @Override
    public Registration scheduleAtFixedRate(
            String name, Duration initialDelay, Duration interval, Runnable task) {
        if (closed.get()) throw new IllegalStateException("service plugin executor is closed");
        if (interval == null || interval.isZero() || interval.isNegative()) {
            throw new IllegalArgumentException("interval must be positive");
        }
        var future = timers.scheduleAtFixedRate(() -> {
            if (!closed.get()) run(name, task);
        }, Math.max(0, initialDelay == null ? 0 : initialDelay.toMillis()),
                interval.toMillis(), TimeUnit.MILLISECONDS);
        return () -> future.cancel(true);
    }

    Registration schedule(Duration delay, Runnable task) {
        if (closed.get()) throw new IllegalStateException("service plugin executor is closed");
        Duration checked = delay == null ? Duration.ZERO : delay;
        if (checked.isNegative()) throw new IllegalArgumentException("delay must not be negative");
        var future = timers.schedule(() -> {
            if (!closed.get()) task.run();
        }, checked.toMillis(), TimeUnit.MILLISECONDS);
        return () -> future.cancel(true);
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        timers.shutdownNow();
        tasks.shutdownNow();
    }
}
