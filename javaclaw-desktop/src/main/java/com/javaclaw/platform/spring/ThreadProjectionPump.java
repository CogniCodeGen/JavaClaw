package com.javaclaw.platform.spring;

import com.javaclaw.framework.store.ThreadRolloutProjector;
import com.javaclaw.platform.execution.*;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;

/** Dispatches projection work through the shared managed executor. */
final class ThreadProjectionPump implements AutoCloseable {
    private final TaskScope tasks;
    private final TriggerHandle trigger;
    private final AtomicBoolean running = new AtomicBoolean();
    ThreadProjectionPump(ManagedTaskExecutor executor, ThreadRolloutProjector projector) {
        tasks = executor.openScope("thread-projections", 1);
        trigger = executor.scheduleTriggerAtFixedRate(Duration.ofSeconds(1), Duration.ofSeconds(1), () -> {
            if (!running.compareAndSet(false, true)) return;
            try {
                tasks.submit(TaskSpec.io("project-thread-outbox"), context -> {
                    try { projector.drain(); } finally { running.set(false); }
                    return null;
                });
            } catch (RuntimeException failure) { running.set(false); }
        });
    }
    @Override public void close() { trigger.close(); tasks.close(); }
}
