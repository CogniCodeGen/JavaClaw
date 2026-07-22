package com.javaclaw.plugin;

import com.javaclaw.plugin.api.exec.ManagedTask;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CountDownLatch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PluginSchedulerTest {

    @Test
    void fastTasksDoNotLeaveGhostHandles() throws Exception {
        PluginScheduler scheduler = new PluginScheduler("race-test",
                new PluginScope.PluginIdentity("race-test", Set.of()), 8);
        try {
            List<ManagedTask> tasks = new ArrayList<>();
            for (int i = 0; i < 1_000; i++) {
                tasks.add(scheduler.submit(() -> {
                }));
            }

            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (System.nanoTime() < deadline
                    && (!tasks.stream().allMatch(ManagedTask::isDone) || scheduler.activeHandleCount() != 0)) {
                Thread.sleep(Duration.ofMillis(5));
            }

            assertTrue(tasks.stream().allMatch(ManagedTask::isDone));
            assertEquals(0, scheduler.activeHandleCount());
        } finally {
            scheduler.shutdown();
        }
    }

    @Test
    void delayedTaskHandleTracksAndCancelsDispatchedExecution() throws Exception {
        PluginScheduler scheduler = new PluginScheduler("delay-test",
                new PluginScope.PluginIdentity("delay-test", Set.of()), 1);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(1);
        try {
            ManagedTask handle = scheduler.schedule(Duration.ZERO, () -> {
                started.countDown();
                try {
                    new CountDownLatch(1).await();
                } finally {
                    finished.countDown();
                }
            });

            assertTrue(started.await(2, TimeUnit.SECONDS));
            assertFalse(handle.isDone(), "定时器已触发不代表实际任务已完成");
            handle.cancel();

            assertTrue(finished.await(2, TimeUnit.SECONDS), "取消应中断已派发的虚拟线程");
            assertTrue(handle.isDone());
            assertEquals(0, scheduler.activeHandleCount());
        } finally {
            scheduler.shutdown();
        }
    }
}
