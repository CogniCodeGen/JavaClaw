package com.javaclaw.infrastructure.agent;

import com.javaclaw.agent.TokenTracker;
import com.javaclaw.framework.api.ModelTokenUsage;
import com.javaclaw.framework.api.ModelUsageFact;
import com.javaclaw.framework.api.RunId;
import com.javaclaw.framework.api.RunScope;
import com.javaclaw.framework.spi.RunUsageObserver;
import com.javaclaw.platform.execution.ManagedTaskExecutor;
import com.javaclaw.platform.execution.TaskScope;
import com.javaclaw.platform.execution.TaskSpec;
import com.javaclaw.platform.execution.TriggerHandle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

/** Projects live usage and coalesces durable reconciliation retries within workspace lifecycle. */
public final class TokenUsageProjectionCoordinator
        implements RunUsageObserver, AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(
            TokenUsageProjectionCoordinator.class);
    private static final Duration[] RETRY_DELAYS = {
            Duration.ofSeconds(1), Duration.ofSeconds(5),
            Duration.ofSeconds(30), Duration.ofSeconds(60)
    };

    private final JdbcTokenUsageProjector projector;
    private final TokenTracker tracker;
    private final ManagedTaskExecutor tasks;
    private final TaskScope scope;
    private final Object retryLock = new Object();
    private final AtomicBoolean closed = new AtomicBoolean();
    private TriggerHandle retryTrigger;
    private int retryAttempt;

    public TokenUsageProjectionCoordinator(
            JdbcTokenUsageProjector projector,
            TokenTracker tracker,
            ManagedTaskExecutor tasks,
            TaskScope scope) {
        this.projector = Objects.requireNonNull(projector, "projector");
        this.tracker = Objects.requireNonNull(tracker, "tracker");
        this.tasks = Objects.requireNonNull(tasks, "tasks");
        this.scope = Objects.requireNonNull(scope, "scope");
        try {
            reconcileNow();
        } catch (RuntimeException failure) {
            log.warn("启动时模型用量补偿失败，将异步重试", failure);
            scheduleRetry();
        }
    }

    @Override
    public void recorded(
            RunId runId, RunScope scope, long inputTokens,
            long outputTokens, BigDecimal cost) {
        // Compatibility path for callers without a durable modelCallId.
        tracker.recordModelUsage("agent-run:" + runId.value(), inputTokens, outputTokens);
    }

    @Override
    public void recorded(ModelUsageFact fact) {
        try {
            if (projector.project(fact)) tracker.applyProjectedUsage(fact);
        } catch (RuntimeException failure) {
            scheduleRetry();
            throw failure;
        }
    }

    @Override
    public void recorded(
            RunId runId, RunScope scope, ModelTokenUsage usage, BigDecimal cost) {
        tracker.recordModelUsage("agent-run:" + runId.value(), usage);
    }

    private void reconcileNow() {
        int applied = projector.reconcile();
        if (applied > 0) tracker.reloadPersistedUsage();
        synchronized (retryLock) {
            retryAttempt = 0;
        }
    }

    private void scheduleRetry() {
        synchronized (retryLock) {
            if (closed.get() || retryTrigger != null) return;
            Duration delay = RETRY_DELAYS[Math.min(retryAttempt, RETRY_DELAYS.length - 1)];
            retryAttempt++;
            retryTrigger = tasks.scheduleTrigger(delay, this::submitRetry);
        }
    }

    private void submitRetry() {
        synchronized (retryLock) {
            retryTrigger = null;
        }
        if (closed.get()) return;
        try {
            var handle = scope.submit(TaskSpec.io("token-usage-projection-reconcile"), context -> {
                reconcileNow();
                return null;
            });
            handle.completion().whenComplete((ignored, failure) -> {
                if (failure != null && !closed.get()) {
                    log.warn("模型用量补偿重试失败", failure);
                    scheduleRetry();
                }
            });
        } catch (RejectedExecutionException rejected) {
            if (!closed.get()) scheduleRetry();
        }
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        synchronized (retryLock) {
            if (retryTrigger != null) retryTrigger.cancel();
            retryTrigger = null;
        }
    }
}
