package com.javaclaw.schedule;

import com.javaclaw.agent.ToolCallOrigin;
import com.javaclaw.api.conversation.ConversationCallbacks;
import com.javaclaw.api.conversation.ConversationOutcome;
import com.javaclaw.platform.execution.ManagedTaskExecutor;
import com.javaclaw.platform.execution.TaskScope;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScheduleManagedExecutionTest {

    @TempDir
    Path dataDir;

    @Test
    void taskBodyUsesVirtualThreadAndWorkspaceCloseCancelsIt() throws Exception {
        AtomicBoolean virtual = new AtomicBoolean(false);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch stopped = new CountDownLatch(1);
        ScheduledTaskRunner runner = new ScheduledTaskRunner() {
            @Override
            public void run(ScheduledRunControl control, ToolCallOrigin origin, String prompt,
                            ConversationCallbacks callbacks) {
                virtual.set(Thread.currentThread().isVirtual());
                started.countDown();
                try {
                    while (!control.isCancelled()) {
                        Thread.sleep(10);
                    }
                    callbacks.onTerminal(ConversationOutcome.cancelled(
                            control.cancellationReason()));
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    callbacks.onTerminal(ConversationOutcome.cancelled(
                            control.cancellationReason()));
                } finally {
                    stopped.countDown();
                }
            }

            @Override
            public void shutdown() { }
        };

        try (ManagedTaskExecutor executor = new ManagedTaskExecutor()) {
            TaskScope scope = executor.openScope("schedule-managed-test", 1);
            ScheduleManager manager = new ScheduleManager(
                    ScheduleTestStoreFactory.create(dataDir), "ws",
                    new ScheduleManagerTest.FakeScheduleBackend(), scope, runner);
            manager.init(runner);
            ScheduledTask task = new ScheduledTask("managed", "Managed");
            task.setPrompt("test");
            task.setEnabled(false);
            manager.saveNewTask(task);

            assertEquals(ScheduleManager.RunNowResult.STARTED,
                    manager.runNow(task.getId(), true));
            assertTrue(started.await(2, TimeUnit.SECONDS));
            assertTrue(virtual.get(), "定时任务正文必须运行在虚拟线程");

            manager.shutdown();
            assertTrue(stopped.await(2, TimeUnit.SECONDS));
            assertFalse(manager.isActive(task.getId()));
        }
    }

    @Test
    void productionQuartzBackendBindsJobToWorkspaceManager() {
        try (ManagedTaskExecutor executor = new ManagedTaskExecutor()) {
            TaskScope scope = executor.openScope("schedule-quartz-test", 1);
            ScheduleManager manager = new ScheduleManager(
                    ScheduleTestStoreFactory.create(dataDir), "ws", scope);
            try {
                ScheduledTask task = new ScheduledTask("quartz", "Quartz");
                task.setPrompt("test");
                task.setIntervalInMinutes(60);
                task.setEnabled(true);

                manager.saveNewTask(task);

                assertTrue(manager.getNextFireTime(task.getId()) != null);
            } finally {
                manager.shutdown();
            }
        }
    }
}
