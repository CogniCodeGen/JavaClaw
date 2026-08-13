package com.javaclaw.infrastructure.agent;

import com.javaclaw.framework.spi.BackgroundJob;
import com.javaclaw.framework.spi.BackgroundJobScheduler;
import com.javaclaw.platform.execution.ManagedTaskExecutor;
import com.javaclaw.platform.execution.TaskHandle;
import com.javaclaw.platform.execution.TaskSpec;
import com.javaclaw.platform.execution.TriggerHandle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Runs extension maintenance work on the one process-wide managed executor. */
public final class ManagedBackgroundJobScheduler implements BackgroundJobScheduler {
    private static final Logger log = LoggerFactory.getLogger(ManagedBackgroundJobScheduler.class);
    private final ManagedTaskExecutor tasks;

    public ManagedBackgroundJobScheduler(ManagedTaskExecutor tasks) {
        this.tasks = Objects.requireNonNull(tasks, "tasks");
    }

    @Override
    public Registration schedule(String extensionId, BackgroundJob job) {
        Objects.requireNonNull(extensionId, "extensionId");
        Objects.requireNonNull(job, "job");
        Duration interval = Objects.requireNonNull(job.interval(), "job interval");
        if (interval.isZero() || interval.isNegative()) {
            throw new IllegalArgumentException("background job interval must be positive: " + job.id());
        }
        JobRegistration registration = new JobRegistration(extensionId, job);
        registration.start(interval);
        return registration;
    }

    private final class JobRegistration implements Registration {
        private final String extensionId;
        private final BackgroundJob job;
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private final AtomicBoolean running = new AtomicBoolean();
        private final AtomicReference<TaskHandle<Void>> active = new AtomicReference<>();
        private volatile TriggerHandle trigger;

        private JobRegistration(String extensionId, BackgroundJob job) {
            this.extensionId = extensionId;
            this.job = job;
        }

        private void start(Duration interval) {
            trigger = tasks.scheduleTriggerAtFixedRate(interval, interval, this::dispatch);
        }

        private void dispatch() {
            if (cancelled.get() || !running.compareAndSet(false, true)) return;
            try {
                TaskHandle<Void> handle = tasks.submit(TaskSpec.io(taskName()), context -> {
                    job.run(() -> cancelled.get()
                            || context.cancellation().isCancellationRequested()
                            || Thread.currentThread().isInterrupted());
                    return null;
                });
                active.set(handle);
                handle.completion().whenComplete((ignored, failure) -> {
                    active.compareAndSet(handle, null);
                    running.set(false);
                    if (failure != null && !cancelled.get()) {
                        log.warn("扩展后台任务失败 {}:{}: {}",
                                extensionId, job.id(), failure.getMessage());
                    }
                });
                if (cancelled.get()) handle.cancel();
            } catch (RuntimeException failure) {
                running.set(false);
                if (!cancelled.get()) throw failure;
            }
        }

        private String taskName() {
            return ("extension-" + extensionId + "-" + job.id())
                    .replaceAll("[^a-zA-Z0-9._-]", "-");
        }

        @Override
        public void close() {
            if (!cancelled.compareAndSet(false, true)) return;
            TriggerHandle scheduled = trigger;
            if (scheduled != null) scheduled.cancel();
            TaskHandle<Void> handle = active.get();
            if (handle != null) handle.cancel();
        }
    }
}
