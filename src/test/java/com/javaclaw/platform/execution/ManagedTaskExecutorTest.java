package com.javaclaw.platform.execution;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ManagedTaskExecutorTest {

    private ManagedTaskExecutor executor;

    @AfterEach
    void closeExecutor() {
        MDC.clear();
        if (executor != null) {
            executor.close();
        }
    }

    @Test
    void ioTaskUsesVirtualThreadAndPropagatesTaskAndMdcContext() throws Exception {
        executor = new ManagedTaskExecutor(testLimits());
        MDC.put("workspaceId", "workspace-a");

        TaskHandle<ObservedContext> handle = executor.submit(TaskSpec.io("context"), context ->
                new ObservedContext(Thread.currentThread().isVirtual(), context.taskId(),
                        TaskContext.current().orElseThrow().taskId(), MDC.get("workspaceId")));
        MDC.clear();

        ObservedContext observed = handle.completion().get(2, TimeUnit.SECONDS);
        assertTrue(observed.virtualThread());
        assertEquals(handle.id(), observed.explicitTaskId());
        assertEquals(handle.id(), observed.scopedTaskId());
        assertEquals("workspace-a", observed.workspaceId());
        assertEquals(TaskState.SUCCEEDED, handle.state());
    }

    @Test
    void cpuTaskUsesBoundedPlatformPool() throws Exception {
        executor = new ManagedTaskExecutor(testLimits());

        TaskHandle<Boolean> handle = executor.submit(TaskSpec.cpu("parse"),
                context -> Thread.currentThread().isVirtual());

        assertFalse(handle.completion().get(2, TimeUnit.SECONDS));
    }

    @Test
    void browserAndProcessTasksUseTheirVirtualThreadPools() throws Exception {
        executor = new ManagedTaskExecutor(testLimits());

        TaskHandle<ThreadObservation> browser = executor.submit(
                TaskSpec.browser("browser", null), context -> observeThread());
        TaskHandle<ThreadObservation> process = executor.submit(
                TaskSpec.process("process"), context -> observeThread());

        ThreadObservation browserThread = browser.completion().get(2, TimeUnit.SECONDS);
        ThreadObservation processThread = process.completion().get(2, TimeUnit.SECONDS);
        assertTrue(browserThread.virtual());
        assertTrue(browserThread.name().startsWith("javaclaw-browser-"));
        assertTrue(processThread.virtual());
        assertTrue(processThread.name().startsWith("javaclaw-process-"));
    }

    @Test
    void ioConcurrencyNeverExceedsConfiguredQuota() throws Exception {
        executor = new ManagedTaskExecutor(testLimits());
        CountDownLatch twoEntered = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger active = new AtomicInteger();
        AtomicInteger maximum = new AtomicInteger();
        List<TaskHandle<Integer>> handles = new ArrayList<>();

        for (int index = 0; index < 6; index++) {
            handles.add(executor.submit(TaskSpec.io("limited-" + index), context -> {
                int now = active.incrementAndGet();
                maximum.accumulateAndGet(now, Math::max);
                twoEntered.countDown();
                try {
                    release.await();
                    return now;
                } finally {
                    active.decrementAndGet();
                }
            }));
        }

        assertTrue(twoEntered.await(2, TimeUnit.SECONDS));
        assertEquals(2, maximum.get());
        release.countDown();
        for (TaskHandle<Integer> handle : handles) {
            assertNotNull(handle.completion().get(2, TimeUnit.SECONDS));
        }
        assertEquals(2, maximum.get());
    }

    @Test
    void sameBrowserSerializationKeyRunsOneTaskAtATime() throws Exception {
        executor = new ManagedTaskExecutor(testLimits());
        AtomicInteger active = new AtomicInteger();
        AtomicInteger maximum = new AtomicInteger();
        List<TaskHandle<Void>> handles = new ArrayList<>();

        for (int index = 0; index < 4; index++) {
            handles.add(executor.submit(TaskSpec.browser("page-" + index, "page-1"), context -> {
                int now = active.incrementAndGet();
                maximum.accumulateAndGet(now, Math::max);
                try {
                    Thread.sleep(20);
                    return null;
                } finally {
                    active.decrementAndGet();
                }
            }));
        }

        for (TaskHandle<Void> handle : handles) {
            handle.completion().get(2, TimeUnit.SECONDS);
        }
        assertEquals(1, maximum.get());
    }

    @Test
    void timeoutCancelsTaskAndInterruptsCarrier() throws Exception {
        executor = new ManagedTaskExecutor(testLimits());
        CountDownLatch started = new CountDownLatch(1);
        TaskSpec spec = TaskSpec.io("timeout").withTimeout(Duration.ofMillis(80));

        TaskHandle<Void> handle = executor.submit(spec, context -> {
            started.countDown();
            new CountDownLatch(1).await();
            return null;
        });

        assertTrue(started.await(1, TimeUnit.SECONDS));
        ExecutionException failure = assertThrows(ExecutionException.class,
                () -> handle.completion().get(2, TimeUnit.SECONDS));
        assertTrue(failure.getCause() instanceof TimeoutException);
        assertEquals(TaskState.CANCELLED, handle.state());
    }

    @Test
    void taskFailureAndCancellationReachDistinctTerminalStates() throws Exception {
        executor = new ManagedTaskExecutor(testLimits());

        TaskHandle<Void> failed = executor.submit(TaskSpec.io("failed"), context -> {
            throw new IllegalStateException("boom");
        });
        ExecutionException failure = assertThrows(ExecutionException.class,
                () -> failed.completion().get(2, TimeUnit.SECONDS));
        assertEquals("boom", failure.getCause().getMessage());
        assertEquals(TaskState.FAILED, failed.state());
        assertFalse(failed.cancel());

        TaskHandle<Void> cancelled = executor.submit(TaskSpec.io("cancelled"), context -> {
            throw new CancellationException("cooperative stop");
        });
        assertThrows(CancellationException.class,
                () -> cancelled.completion().get(2, TimeUnit.SECONDS));
        assertEquals(TaskState.CANCELLED, cancelled.state());
        assertFalse(cancelled.cancel());

        TaskHandle<String> succeeded = executor.submit(
                TaskSpec.io("succeeded"), context -> "done");
        assertEquals("done", succeeded.completion().get(2, TimeUnit.SECONDS));
        succeeded.close();
        assertEquals(TaskState.SUCCEEDED, succeeded.state());
    }

    @Test
    void schedulerSupportsOneShotAndFixedRateTriggers() throws Exception {
        executor = new ManagedTaskExecutor(testLimits());
        CountDownLatch oneShotRan = new CountDownLatch(1);

        TriggerHandle oneShot = executor.scheduleTrigger(Duration.ZERO, oneShotRan::countDown);

        assertTrue(oneShotRan.await(1, TimeUnit.SECONDS));
        awaitCondition(oneShot::isCancelled);
        assertFalse(oneShot.cancel());

        CountDownLatch periodicRuns = new CountDownLatch(2);
        TriggerHandle periodic = executor.scheduleTriggerAtFixedRate(
                Duration.ZERO, Duration.ofMillis(10), periodicRuns::countDown);
        assertTrue(periodicRuns.await(1, TimeUnit.SECONDS));
        assertTrue(periodic.cancel());
        assertTrue(periodic.isCancelled());
        assertFalse(periodic.cancel());
    }

    @Test
    void schedulerRejectsInvalidTimingAndNullActions() {
        executor = new ManagedTaskExecutor(testLimits());

        assertThrows(IllegalArgumentException.class,
                () -> executor.scheduleTrigger(null, () -> { }));
        assertThrows(IllegalArgumentException.class,
                () -> executor.scheduleTrigger(Duration.ofNanos(-1), () -> { }));
        assertThrows(NullPointerException.class,
                () -> executor.scheduleTrigger(Duration.ZERO, null));
        assertThrows(IllegalArgumentException.class,
                () -> executor.scheduleTriggerAtFixedRate(
                        Duration.ZERO, Duration.ZERO, () -> { }));
        assertThrows(IllegalArgumentException.class,
                () -> executor.scheduleTriggerAtFixedRate(
                        Duration.ZERO, Duration.ofNanos(-1), () -> { }));
        assertThrows(IllegalArgumentException.class,
                () -> executor.scheduleTriggerAtFixedRate(
                        Duration.ZERO, null, () -> { }));
    }

    @Test
    void closingScopeCancelsOnlyItsOwnTasksAndRejectsNewOnes() throws Exception {
        executor = new ManagedTaskExecutor(testLimits());
        TaskScope first = executor.openScope("first", 1);
        TaskScope second = executor.openScope("second", 1);
        CountDownLatch started = new CountDownLatch(2);
        ManagedTask<Void> waiting = context -> {
            started.countDown();
            new CountDownLatch(1).await();
            return null;
        };
        TaskHandle<Void> cancelled = first.submit(TaskSpec.io("first-task"), waiting);
        TaskHandle<Void> survivor = second.submit(TaskSpec.io("second-task"), waiting);
        assertTrue(started.await(1, TimeUnit.SECONDS));

        first.close();

        assertEquals(TaskState.CANCELLED, cancelled.state());
        assertFalse(survivor.state().isTerminal());
        assertThrows(java.util.concurrent.RejectedExecutionException.class,
                () -> first.submit(TaskSpec.io("late"), context -> null));
        second.close();
    }

    @Test
    void scopeStillAwaitsPhysicalTerminationAfterHandleWasAlreadyCancelled() throws Exception {
        executor = new ManagedTaskExecutor(testLimits());
        TaskScope scope = executor.openScope("termination", 1);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch cleanupFinished = new CountDownLatch(1);
        AtomicBoolean interrupted = new AtomicBoolean();
        TaskHandle<Void> handle = scope.submit(TaskSpec.io("cancel-before-close"), context -> {
            started.countDown();
            try {
                new CountDownLatch(1).await();
            } catch (InterruptedException expected) {
                interrupted.set(true);
            } finally {
                Thread.sleep(80);
                cleanupFinished.countDown();
            }
            return null;
        });
        assertTrue(started.await(1, TimeUnit.SECONDS));

        handle.cancel();
        scope.close();

        assertTrue(interrupted.get());
        assertEquals(0, cleanupFinished.getCount(),
                "作用域关闭必须等待已取消任务的 finally 清理完成");
    }

    @Test
    void scopeQuotaLimitsTasksAndReportsOnlyLiveHandles() throws Exception {
        executor = new ManagedTaskExecutor(testLimits());
        TaskScope scope = executor.openScope("quota", 1);
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch secondStarted = new CountDownLatch(1);

        TaskHandle<Void> first = scope.submit(TaskSpec.io("first"), context -> {
            firstStarted.countDown();
            releaseFirst.await();
            return null;
        });
        TaskHandle<Void> second = scope.submit(TaskSpec.io("second"), context -> {
            secondStarted.countDown();
            return null;
        });

        assertTrue(firstStarted.await(1, TimeUnit.SECONDS));
        assertFalse(secondStarted.await(80, TimeUnit.MILLISECONDS));
        assertEquals(2, scope.activeTaskCount());
        releaseFirst.countDown();
        first.completion().get(1, TimeUnit.SECONDS);
        second.completion().get(1, TimeUnit.SECONDS);
        awaitCondition(() -> scope.activeTaskCount() == 0);
        scope.close();
        scope.close();
    }

    @Test
    void constructorsValidateLimitsSpecsAndScopes() {
        for (int invalidIndex = 0; invalidIndex < 5; invalidIndex++) {
            int[] values = {1, 1, 1, 1, 1};
            values[invalidIndex] = 0;
            assertThrows(IllegalArgumentException.class, () -> new ExecutionLimits(
                    values[0], values[1], values[2], values[3], values[4]));
        }
        ExecutionLimits defaults = ExecutionLimits.defaults();
        assertEquals(256, defaults.ioConcurrency());
        assertEquals(256, defaults.cpuQueueCapacity());
        assertEquals(4, defaults.browserConcurrency());
        assertEquals(8, defaults.processConcurrency());

        assertThrows(IllegalArgumentException.class,
                () -> new TaskSpec(" ", Workload.IO, Duration.ZERO, null));
        assertThrows(NullPointerException.class,
                () -> new TaskSpec("task", null, Duration.ZERO, null));
        assertThrows(IllegalArgumentException.class,
                () -> new TaskSpec("task", Workload.IO, Duration.ofNanos(-1), null));
        TaskSpec normalized = new TaskSpec("task", Workload.IO, null, " ");
        assertEquals(Duration.ZERO, normalized.timeout());
        assertNull(normalized.serializationKey());

        executor = new ManagedTaskExecutor(testLimits());
        assertThrows(IllegalArgumentException.class, () -> executor.openScope(" ", 1));
        assertThrows(IllegalArgumentException.class, () -> executor.openScope("scope", 0));
    }

    @Test
    void closingExecutorCancelsTasksAndRejectsEverySubmissionPath() throws Exception {
        executor = new ManagedTaskExecutor(testLimits());
        CountDownLatch started = new CountDownLatch(1);
        TaskHandle<Void> running = executor.submit(TaskSpec.io("running"), context -> {
            started.countDown();
            new CountDownLatch(1).await();
            return null;
        });
        assertTrue(started.await(1, TimeUnit.SECONDS));

        executor.close();
        executor.close();

        assertEquals(TaskState.CANCELLED, running.state());
        assertEquals(0, executor.activeTaskCount());
        assertThrows(RejectedExecutionException.class,
                () -> executor.submit(TaskSpec.io("late"), context -> null));
        assertThrows(RejectedExecutionException.class,
                () -> executor.openScope("late", 1));
        assertThrows(RejectedExecutionException.class,
                () -> executor.scheduleTrigger(Duration.ZERO, () -> { }));
        assertThrows(RejectedExecutionException.class,
                () -> executor.scheduleTriggerAtFixedRate(
                        Duration.ZERO, Duration.ofSeconds(1), () -> { }));
    }

    private static ThreadObservation observeThread() {
        Thread thread = Thread.currentThread();
        return new ThreadObservation(thread.isVirtual(), thread.getName());
    }

    private static void awaitCondition(java.util.function.BooleanSupplier condition)
            throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }
        assertTrue(condition.getAsBoolean());
    }

    private ExecutionLimits testLimits() {
        return new ExecutionLimits(2, 1, 4, 4, 2);
    }

    private record ObservedContext(
            boolean virtualThread,
            String explicitTaskId,
            String scopedTaskId,
            String workspaceId) {
    }

    private record ThreadObservation(boolean virtual, String name) { }
}
