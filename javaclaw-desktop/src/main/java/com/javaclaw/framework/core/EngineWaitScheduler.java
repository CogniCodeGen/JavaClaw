package com.javaclaw.framework.core;

import com.javaclaw.platform.execution.ManagedTaskExecutor;
import com.javaclaw.platform.execution.TaskHandle;
import com.javaclaw.platform.execution.TaskScope;
import com.javaclaw.platform.execution.TaskSpec;
import com.javaclaw.platform.execution.TriggerHandle;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

/** 引擎等待的托管调度作用域；计时线程只派发，运行状态仍由 AgentEngine 改写。 */
final class EngineWaitScheduler implements AutoCloseable {
    private final ManagedTaskExecutor tasks;
    private final TaskScope scope;
    private final boolean owned;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Set<Pending> pending = ConcurrentHashMap.newKeySet();

    EngineWaitScheduler(ManagedTaskExecutor shared) {
        owned = shared == null;
        tasks = owned ? new ManagedTaskExecutor() : shared;
        scope = tasks.openScope("agent-interaction-waits", 64);
    }

    TriggerHandle schedule(Runnable action, long delayMillis) {
        if (closed.get()) throw new RejectedExecutionException("引擎等待调度已关闭");
        Pending value = new Pending(action);
        pending.add(value);
        try {
            value.bindTimer(tasks.scheduleTrigger(Duration.ofMillis(delayMillis), value::dispatch));
            if (closed.get()) value.cancel();
            return value;
        } catch (RuntimeException failure) {
            value.cancel();
            throw failure;
        }
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        List.copyOf(pending).forEach(Pending::cancel);
        scope.close();
        if (owned) tasks.close();
    }

    private final class Pending implements TriggerHandle {
        private final Runnable action;
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private volatile TriggerHandle timer;
        private volatile TaskHandle<Void> task;
        private volatile boolean started;

        Pending(Runnable action) { this.action = java.util.Objects.requireNonNull(action, "action"); }

        void bindTimer(TriggerHandle timer) {
            this.timer = timer;
            if (cancelled.get()) timer.cancel();
        }

        void dispatch() {
            if (cancelled.get() || closed.get()) return;
            try {
                TaskHandle<Void> submitted = scope.submit(TaskSpec.io("interaction-wait-trigger"), context -> {
                    if (begin()) action.run();
                    return null;
                });
                task = submitted;
                if (cancelled.get() && !started) submitted.cancel();
                submitted.termination().whenComplete((ignored, failure) -> pending.remove(this));
            } catch (RuntimeException failure) {
                pending.remove(this);
                throw failure;
            }
        }

        private synchronized boolean begin() {
            if (cancelled.get() || closed.get()) return false;
            started = true;
            return true;
        }

        @Override public synchronized boolean cancel() {
            if (!cancelled.compareAndSet(false, true)) return false;
            TriggerHandle currentTimer = timer;
            if (currentTimer != null) currentTimer.cancel();
            TaskHandle<Void> currentTask = task;
            // 普通撤销与原 cancel(false) 一致，不中断已开始的状态机回调；引擎关闭再由作用域统一收尾。
            if (currentTask != null && !started) currentTask.cancel();
            pending.remove(this);
            return true;
        }

        @Override public boolean isCancelled() { return cancelled.get(); }
    }
}
