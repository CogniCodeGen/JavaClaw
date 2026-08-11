package com.javaclaw.plugin;

import com.javaclaw.platform.execution.ManagedTaskExecutor;
import com.javaclaw.plugin.api.exec.TaskHandle;
import com.javaclaw.plugin.api.exec.TaskState;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PluginSchedulerTest {

    @Test
    void fastTasksDoNotLeaveGhostHandles() throws Exception {
        try (ManagedTaskExecutor executor = new ManagedTaskExecutor()) {
            PluginScheduler scheduler = scheduler("race-test", 8, executor);
            try {
                List<TaskHandle<Void>> tasks = new ArrayList<>();
                for (int i = 0; i < 1_000; i++) {
                    tasks.add(scheduler.submit("fast-" + i, context -> {
                    }));
                }

                await(() -> tasks.stream().allMatch(task -> task.state().isTerminal())
                        && scheduler.activeHandleCount() == 0);

                assertTrue(tasks.stream().allMatch(
                        task -> task.state() == TaskState.SUCCEEDED));
                assertEquals(0, scheduler.activeHandleCount());
            } finally {
                scheduler.shutdown();
            }
        }
    }

    @Test
    void delayedTaskHandleTracksAndCancelsDispatchedExecution() throws Exception {
        try (ManagedTaskExecutor executor = new ManagedTaskExecutor()) {
            PluginScheduler scheduler = scheduler("delay-test", 1, executor);
            CountDownLatch started = new CountDownLatch(1);
            CountDownLatch finished = new CountDownLatch(1);
            try {
                TaskHandle<Void> handle = scheduler.schedule(
                        "blocked-delay", Duration.ZERO, context -> {
                            started.countDown();
                            try {
                                new CountDownLatch(1).await();
                            } finally {
                                finished.countDown();
                            }
                        });

                assertTrue(started.await(2, TimeUnit.SECONDS));
                assertFalse(handle.state().isTerminal(),
                        "定时器已触发不代表实际任务已完成");
                assertTrue(handle.cancel());

                assertTrue(finished.await(2, TimeUnit.SECONDS),
                        "取消应中断已派发的虚拟线程");
                assertEquals(TaskState.CANCELLED, handle.state());
                assertEquals(0, scheduler.activeHandleCount());
            } finally {
                scheduler.shutdown();
            }
        }
    }

    @Test
    void taskReceivesStableIdAndCooperativeCancellation() throws Exception {
        try (ManagedTaskExecutor executor = new ManagedTaskExecutor()) {
            PluginScheduler scheduler = scheduler("context-test", 1, executor);
            CountDownLatch started = new CountDownLatch(1);
            AtomicReference<String> seenId = new AtomicReference<>();
            AtomicReference<Boolean> seenCancellation = new AtomicReference<>(false);
            try {
                TaskHandle<Void> handle = scheduler.background("listener", context -> {
                    seenId.set(context.taskId());
                    started.countDown();
                    try {
                        while (!context.cancellation().isCancelled()) {
                            Thread.sleep(5);
                        }
                    } finally {
                        seenCancellation.set(context.cancellation().isCancelled());
                    }
                });

                assertTrue(started.await(2, TimeUnit.SECONDS));
                assertNotNull(seenId.get());
                assertEquals(handle.id(), seenId.get());
                handle.cancel();
                await(seenCancellation::get);
                assertEquals(TaskState.CANCELLED, handle.state());
            } finally {
                scheduler.shutdown();
            }
        }
    }

    @Test
    void fixedRateUsesOneHandleAndDoesNotOverlapIterations() throws Exception {
        try (ManagedTaskExecutor executor = new ManagedTaskExecutor()) {
            PluginScheduler scheduler = scheduler("periodic-test", 4, executor);
            AtomicInteger running = new AtomicInteger();
            AtomicInteger maxRunning = new AtomicInteger();
            AtomicInteger ticks = new AtomicInteger();
            try {
                TaskHandle<Void> handle = scheduler.scheduleAtFixedRate(
                        "ticker", Duration.ZERO, Duration.ofMillis(2), context -> {
                            int current = running.incrementAndGet();
                            maxRunning.accumulateAndGet(current, Math::max);
                            try {
                                ticks.incrementAndGet();
                                Thread.sleep(10);
                            } finally {
                                running.decrementAndGet();
                            }
                        });

                await(() -> ticks.get() >= 3);
                assertEquals(TaskState.RUNNING, handle.state());
                assertEquals(1, maxRunning.get(), "同一个周期任务不得重叠执行");
                handle.close();
                int stoppedAt = ticks.get();
                Thread.sleep(30);
                assertEquals(stoppedAt, ticks.get());
                assertEquals(TaskState.CANCELLED, handle.state());
            } finally {
                scheduler.shutdown();
            }
        }
    }

    private static PluginScheduler scheduler(
            String id, int concurrency, ManagedTaskExecutor executor) {
        return new PluginScheduler(id,
                new PluginScope.PluginIdentity(id, Set.of()), concurrency, executor);
    }

    private static void await(java.util.function.BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }
        assertTrue(condition.getAsBoolean(), "等待条件超时");
    }
}
