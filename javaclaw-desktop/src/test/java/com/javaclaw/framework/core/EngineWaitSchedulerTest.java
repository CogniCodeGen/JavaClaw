package com.javaclaw.framework.core;

import com.javaclaw.platform.execution.ManagedTaskExecutor;
import com.javaclaw.platform.execution.TaskSpec;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class EngineWaitSchedulerTest {
    @Test
    void triggerDispatchesOffTheTimerAndClosingDoesNotCloseSharedTasks() throws Exception {
        try (var tasks = new ManagedTaskExecutor()) {
            var scheduler = new EngineWaitScheduler(tasks);
            var entered = new CompletableFuture<Boolean>();
            var release = new CountDownLatch(1);
            try {
                scheduler.schedule(() -> {
                    entered.complete(Thread.currentThread().isVirtual());
                    try {
                        assertTrue(release.await(5, TimeUnit.SECONDS));
                    } catch (InterruptedException cancelled) {
                        Thread.currentThread().interrupt();
                    }
                }, 0);
                assertTrue(entered.get(5, TimeUnit.SECONDS));
                var timerAvailable = new CompletableFuture<Void>();
                tasks.scheduleTrigger(Duration.ZERO, () -> timerAvailable.complete(null));
                timerAvailable.get(5, TimeUnit.SECONDS);
            } finally {
                release.countDown();
                scheduler.close();
            }
            scheduler.close();
            assertThrows(java.util.concurrent.RejectedExecutionException.class, () -> scheduler.schedule(() -> { }, 0));
            assertEquals(7, tasks.submit(TaskSpec.io("shared-still-open"), context -> 7)
                    .completion().get(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void cancellingBeforeDispatchAndClosingTheScopePreventLateActions() throws Exception {
        try (var tasks = new ManagedTaskExecutor(); var scheduler = new EngineWaitScheduler(tasks)) {
            var cancelledAction = new CompletableFuture<Void>();
            var cancelled = scheduler.schedule(() -> cancelledAction.complete(null), 60_000);
            assertTrue(cancelled.cancel());
            assertFalse(cancelled.cancel());
            assertTrue(cancelled.isCancelled());
            var closeAction = new CompletableFuture<Void>();
            var pending = scheduler.schedule(() -> closeAction.complete(null), 60_000);
            scheduler.close();
            assertTrue(pending.isCancelled());
            assertFalse(cancelledAction.isDone());
            assertFalse(closeAction.isDone());
        }
    }

    @Test
    void standaloneScopeOwnsItsExecutorAndRejectsInvalidDelay() {
        try (var scheduler = new EngineWaitScheduler(null)) {
            assertThrows(IllegalArgumentException.class, () -> scheduler.schedule(() -> { }, -1));
            assertThrows(NullPointerException.class, () -> scheduler.schedule(null, 1));
        }
    }
}
