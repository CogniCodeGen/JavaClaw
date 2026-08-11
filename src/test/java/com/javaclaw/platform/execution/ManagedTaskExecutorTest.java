package com.javaclaw.platform.execution;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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

    private ExecutionLimits testLimits() {
        return new ExecutionLimits(2, 1, 4, 4, 2);
    }

    private record ObservedContext(
            boolean virtualThread,
            String explicitTaskId,
            String scopedTaskId,
            String workspaceId) {
    }
}
