package com.javaclaw.schedule;

import com.javaclaw.agent.ToolCallOrigin;
import com.javaclaw.api.conversation.ConversationCallbacks;
import com.javaclaw.api.conversation.ConversationEvent;
import com.javaclaw.api.conversation.ConversationOutcome;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.quartz.JobDetail;
import org.quartz.JobKey;
import org.quartz.SchedulerException;
import org.quartz.Trigger;
import org.quartz.TriggerKey;
import org.quartz.spi.OperableTrigger;

import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScheduleManagerBehaviorTest {

    @TempDir
    Path dataDirectory;

    private ScheduleManager manager;
    private ExecutorService executor;

    @AfterEach
    void closeManager() {
        if (manager != null) manager.shutdown();
        if (executor != null) executor.shutdownNow();
    }

    @Test
    void lifecycleReloadsRunnersAndSurvivesBackendCleanupFailures() {
        FaultBackend backend = new FaultBackend();
        CountingRunner first = new CountingRunner();
        CountingRunner second = new CountingRunner();
        manager = manager(backend, first);
        ScheduledTask enabled = save("reload-enabled", true);
        assertNotNull(backend.trigger(enabled.getId()));

        backend.failClear = true;
        manager.reload(second);
        assertEquals(1, first.shutdowns.get());
        assertNotNull(backend.trigger(enabled.getId()));
        manager.reload(second);
        assertEquals(0, second.shutdowns.get());

        manager.suspendForRuntimeTransition();
        assertEquals(1, second.shutdowns.get());
        assertEquals(ScheduleManager.RunNowResult.UNSUPPORTED,
                manager.runNow(enabled.getId(), false));
        manager.suspendForRuntimeTransition();

        backend.failShutdown = true;
        manager.shutdown();
        manager = null;
        assertTrue(backend.shutdownAttempts.get() > 0);
    }

    @Test
    void queriesReturnSnapshotsAndIsolateBackendFailures() {
        FaultBackend backend = new FaultBackend();
        CountingRunner runner = new CountingRunner();
        manager = manager(backend, runner);

        ScheduledTask draft = manager.createDraft("草稿");
        assertEquals(8, draft.getId().length());
        assertEquals("草稿", draft.getName());
        assertNull(manager.getTask(draft.getId()));
        assertNull(manager.getTask(null));
        assertEquals(2, manager.getAllTasks().size());
        assertTrue(manager.isBuiltin("sys:cmd-session-cleanup"));
        assertFalse(manager.isBuiltin("user"));

        ScheduledTask saved = save("query", true);
        ScheduledTask snapshot = manager.getTask(saved.getId());
        snapshot.setName("外部修改");
        assertEquals("Task query", manager.getTask(saved.getId()).getName());
        assertEquals(3, manager.getAllTasks().size());
        assertFalse(manager.isRunning(saved.getId()));
        assertFalse(manager.isActive(saved.getId()));

        LocalDateTime next = manager.getNextFireTime(saved.getId());
        assertNotNull(next);
        assertNull(manager.getNextFireTime("missing"));
        backend.failGet = true;
        assertNull(manager.getNextFireTime(saved.getId()));
        backend.failGet = false;
        backend.forceNullNextFire = true;
        assertNull(manager.getNextFireTime(saved.getId()));
    }

    @Test
    void definitionsRejectReservedOrIncompleteDataAndCrudRemainsConsistent() {
        FaultBackend backend = new FaultBackend();
        manager = manager(backend, new CountingRunner());

        assertThrows(IllegalArgumentException.class, () -> manager.saveNewTask(null));
        ScheduledTask builtinFlag = task("flagged", false);
        builtinFlag.setBuiltin(true);
        assertThrows(IllegalArgumentException.class, () -> manager.saveNewTask(builtinFlag));
        assertThrows(IllegalArgumentException.class,
                () -> manager.saveNewTask(task("sys:reserved", false)));
        assertThrows(IllegalArgumentException.class,
                () -> manager.saveNewTask(task("sys:cmd-session-cleanup", false)));

        ScheduledTask missingId = task("id", false);
        missingId.setId(null);
        assertThrows(IllegalArgumentException.class, () -> manager.saveNewTask(missingId));
        missingId.setId(" ");
        assertThrows(IllegalArgumentException.class, () -> manager.saveNewTask(missingId));
        ScheduledTask missingName = task("missing-name", false);
        missingName.setName(null);
        assertThrows(IllegalArgumentException.class, () -> manager.saveNewTask(missingName));
        missingName.setName(" ");
        assertThrows(IllegalArgumentException.class, () -> manager.saveNewTask(missingName));
        ScheduledTask missingPrompt = task("missing-prompt", false);
        missingPrompt.setPrompt(null);
        assertThrows(IllegalArgumentException.class, () -> manager.saveNewTask(missingPrompt));
        missingPrompt.setPrompt(" ");
        assertThrows(IllegalArgumentException.class, () -> manager.saveNewTask(missingPrompt));

        ScheduledTask invalidEnabled = task("invalid-enabled", true);
        invalidEnabled.setTriggerType("cron");
        invalidEnabled.setCronExpression("five fields only * *");
        assertThrows(IllegalArgumentException.class,
                () -> manager.saveNewTask(invalidEnabled));
        ScheduledTask invalidDisabled = task("invalid-disabled", false);
        invalidDisabled.setTriggerType("cron");
        invalidDisabled.setCronExpression("five fields only * *");
        ScheduledTask savedInvalid = manager.saveNewTask(invalidDisabled);
        assertThrows(SchedulePersistenceException.class,
                () -> manager.setEnabled(savedInvalid.getId(), true,
                        ScheduleManager.DisableMode.CANCEL_ACTIVE));
        assertFalse(manager.getTask(savedInvalid.getId()).isEnabled());

        ScheduledTask saved = save("crud", false);
        assertThrows(IllegalArgumentException.class, () -> manager.saveNewTask(saved));
        assertThrows(IllegalArgumentException.class, () -> manager.updateTask(null));
        assertThrows(IllegalArgumentException.class,
                () -> manager.updateTask(manager.getTask("sys:habit-review")));
        assertThrows(IllegalArgumentException.class,
                () -> manager.updateTask(task("sys:any", false)));
        assertThrows(IllegalArgumentException.class,
                () -> manager.updateTask(task("missing", false)));
        assertThrows(IllegalArgumentException.class,
                () -> manager.setEnabled("missing", true,
                        ScheduleManager.DisableMode.CANCEL_ACTIVE));
        assertThrows(IllegalArgumentException.class,
                () -> manager.setEnabled("sys:habit-review", false,
                        ScheduleManager.DisableMode.CANCEL_ACTIVE));

        assertFalse(manager.setEnabled(saved.getId(), false,
                ScheduleManager.DisableMode.CANCEL_ACTIVE).isEnabled());
        ScheduledTask enabled = manager.setEnabled(saved.getId(), true,
                ScheduleManager.DisableMode.CANCEL_ACTIVE);
        assertTrue(enabled.isEnabled());
        assertTrue(manager.setEnabled(saved.getId(), true,
                ScheduleManager.DisableMode.CANCEL_ACTIVE).isEnabled());
        enabled.setName("更新后名称");
        assertEquals("更新后名称", manager.updateTask(enabled).getName());

        ScheduledTask disable = manager.getTask(saved.getId());
        disable.setEnabled(false);
        disable = manager.updateTask(disable);
        assertFalse(disable.isEnabled());
        backend.failDelete = true;
        disable.setDescription("即使 Quartz 删除失败也保存定义");
        assertEquals("即使 Quartz 删除失败也保存定义",
                manager.updateTask(disable).getDescription());
        backend.failDelete = false;

        backend.failSchedule = true;
        ScheduledTask enableByUpdate = manager.getTask(saved.getId());
        enableByUpdate.setEnabled(true);
        assertThrows(SchedulePersistenceException.class,
                () -> manager.updateTask(enableByUpdate));
        assertFalse(manager.getTask(saved.getId()).isEnabled());
        backend.failSchedule = false;

        assertThrows(IllegalArgumentException.class,
                () -> manager.deleteTask("sys:habit-review"));
        manager.deleteTask("missing");
        manager.deleteTasks(null);
        manager.deleteTasks(List.of());
        manager.deleteTasks(List.of("sys:habit-review"));
        ScheduledTask other = save("delete-other", false);
        manager.deleteTasks(List.of("sys:habit-review", other.getId()));
        assertNull(manager.getTask(other.getId()));
        manager.deleteTask(saved.getId());
        assertNull(manager.getTask(saved.getId()));
    }

    @Test
    void builtinActionsReportActualQueueStateAndIsolateListenerFailures() throws Exception {
        FaultBackend backend = new FaultBackend();
        CountingRunner runner = new CountingRunner();
        manager = manager(backend, runner);
        String cleanupId = "sys:cmd-session-cleanup";
        String reviewId = "sys:habit-review";

        assertEquals(ScheduleManager.RunNowResult.UNSUPPORTED,
                manager.runNow(cleanupId, true));
        assertEquals(ScheduleManager.RunNowResult.UNSUPPORTED,
                manager.runNow("sys:missing", true));
        assertThrows(NullPointerException.class, () -> manager.subscribe(null));

        List<String> events = new java.util.concurrent.CopyOnWriteArrayList<>();
        AutoCloseable subscription = manager.subscribe(new ScheduleManager.TaskListener() {
            @Override
            public void onLog(String taskName, String message) {
                events.add("log:" + taskName + ":" + message);
            }

            @Override
            public void onExecutionStarted(String taskId) {
                events.add("start:" + taskId);
            }

            @Override
            public void onExecutionCompleted(String taskId) {
                events.add("complete:" + taskId);
            }
        });
        manager.subscribe(new ScheduleManager.TaskListener() {
            @Override public void onLog(String taskName, String message) { throw new IllegalStateException("log"); }
            @Override public void onExecutionStarted(String taskId) { throw new IllegalStateException("start"); }
            @Override public void onExecutionCompleted(String taskId) { throw new IllegalStateException("done"); }
        });

        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AutoCloseable cleanup = manager.registerBuiltinAction(cleanupId, () -> {
            started.countDown();
            release.await(2, TimeUnit.SECONDS);
            return "清理完成";
        });
        assertTrue(manager.hasBuiltinAction(cleanupId));
        assertEquals(ScheduleManager.RunNowResult.STARTED, manager.runNow(cleanupId, true));
        assertTrue(started.await(2, TimeUnit.SECONDS));
        assertTrue(manager.isRunning(cleanupId));
        assertEquals(ScheduleManager.RunNowResult.ALREADY_ACTIVE,
                manager.runNow(cleanupId, true));
        release.countDown();
        await(() -> !manager.isRunning(cleanupId), Duration.ofSeconds(2));
        assertEquals("成功", manager.getTask(cleanupId).getLastRunStatus());
        assertTrue(events.stream().anyMatch(value -> value.equals("start:" + cleanupId)));
        assertTrue(events.stream().anyMatch(value -> value.equals("complete:" + cleanupId)));

        cleanup.close();
        assertFalse(manager.hasBuiltinAction(cleanupId));
        assertEquals(ScheduleManager.RunNowResult.UNSUPPORTED,
                manager.runNow(cleanupId, true));

        AutoCloseable review = manager.registerBuiltinAction(reviewId, () -> {
            throw new IllegalStateException();
        });
        assertEquals(ScheduleManager.RunNowResult.STARTED, manager.runNow(reviewId, true));
        await(() -> !manager.isRunning(reviewId)
                && manager.getTask(reviewId).getRunCount() == 1, Duration.ofSeconds(2));
        assertEquals("失败", manager.getTask(reviewId).getLastRunStatus());
        manager.recordBuiltinRun("missing", true, 1, "ignored");
        manager.recordBuiltinRun(reviewId, true, 1, "manual record");
        assertEquals(2, manager.getTask(reviewId).getRunCount());

        int eventCount = events.size();
        subscription.close();
        subscription.close();
        manager.recordBuiltinRun(reviewId, true, 1, "after unsubscribe");
        assertEquals(eventCount, events.size());

        manager.shutdown();
        manager.init(runner);
        assertEquals(ScheduleManager.RunNowResult.UNSUPPORTED,
                manager.runNow(reviewId, true));
        review.close();
        manager = null;
    }

    @Test
    void richRunnerEventsProduceOneBoundedTerminalRecordAndOnceTasksStop() throws Exception {
        FaultBackend backend = new FaultBackend();
        ScheduledTaskRunner richRunner = new ScheduledTaskRunner() {
            @Override
            public void run(ScheduledRunControl control, ToolCallOrigin origin, String prompt,
                            ConversationCallbacks callbacks) {
                assertTrue(prompt.startsWith("【定时任务上下文】"));
                assertEquals("scheduled:rich", origin.browserScopeId());
                callbacks.onEvent(new ConversationEvent.Thinking("ignored"));
                callbacks.onEvent(new ConversationEvent.ToolResult("short", "ok"));
                callbacks.onEvent(new ConversationEvent.ToolResult("long", "x".repeat(350)));
                callbacks.onEvent(new ConversationEvent.Hint("hint"));
                callbacks.onEvent(new ConversationEvent.LoopDetected("loop"));
                callbacks.onEvent(new ConversationEvent.Reply("a".repeat(300)));
                callbacks.onEvent(new ConversationEvent.Reply("b".repeat(300)));
                callbacks.onTerminal(ConversationOutcome.completed());
                callbacks.onTerminal(ConversationOutcome.failed(new IllegalStateException("late")));
                callbacks.onEvent(new ConversationEvent.Reply("late"));
            }

            @Override
            public void shutdown() {
            }
        };
        manager = manager(backend, richRunner);
        ScheduledTask rich = task("rich", true);
        rich.setPrompt("p".repeat(250));
        rich.setUnattendedToolsAuthorized(true);
        rich = manager.saveNewTask(rich);
        String richId = rich.getId();
        assertEquals(ScheduleManager.RunNowResult.STARTED,
                manager.runNow(richId, false));
        await(() -> !manager.isActive(richId), Duration.ofSeconds(2));
        ScheduledTask completed = manager.getTask(richId);
        assertEquals("成功", completed.getLastRunStatus());
        assertEquals(1, completed.getRunCount());
        assertEquals(1, completed.getExecRecords().size());
        assertEquals(61, completed.getExecRecords().getFirst().getNote().length());

        manager.reload(new NoTerminalRunner());
        ScheduledTask once = task("once-no-terminal", true);
        once.setTriggerType("once");
        once.setOnceDateTime("2020-01-01 00:00");
        once = manager.saveNewTask(once);
        String onceId = once.getId();
        assertEquals(ScheduleManager.RunNowResult.STARTED,
                manager.runNow(onceId, false));
        await(() -> !manager.isActive(onceId), Duration.ofSeconds(2));
        ScheduledTask autoDisabled = manager.getTask(onceId);
        assertEquals("失败", autoDisabled.getLastRunStatus());
        assertFalse(autoDisabled.isEnabled());

        manager.reload(new NullMessageFailureRunner());
        ScheduledTask failure = save("null-message-failure", true);
        assertEquals(ScheduleManager.RunNowResult.STARTED,
                manager.runNow(failure.getId(), false));
        await(() -> !manager.isActive(failure.getId()), Duration.ofSeconds(2));
        assertEquals("失败", manager.getTask(failure.getId()).getLastRunStatus());
    }

    @Test
    void closedDispatcherRejectsNewUserAndBuiltinRuns() throws Exception {
        FaultBackend backend = new FaultBackend();
        CountingRunner runner = new CountingRunner();
        manager = manager(backend, runner);
        ScheduledTask task = save("closed", false);
        AutoCloseable builtin = manager.registerBuiltinAction(
                "sys:habit-review", () -> "done");

        manager.shutdown();
        manager.init(runner);
        assertEquals(ScheduleManager.RunNowResult.UNSUPPORTED,
                manager.runNow(task.getId(), true));
        assertEquals(ScheduleManager.RunNowResult.UNSUPPORTED,
                manager.runNow("sys:habit-review", true));
        assertFalse(manager.cancelRun("missing"));
        builtin.close();
        manager = null;
    }

    private ScheduleManager manager(FaultBackend backend, ScheduledTaskRunner runner) {
        executor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "schedule-behavior-test");
            thread.setDaemon(true);
            return thread;
        });
        ScheduleManager result = new ScheduleManager(
                ScheduleTestStoreFactory.create(dataDirectory), "workspace",
                backend, executor, runner);
        result.init(runner);
        return result;
    }

    private ScheduledTask save(String id, boolean enabled) {
        return manager.saveNewTask(task(id, enabled));
    }

    private static ScheduledTask task(String id, boolean enabled) {
        ScheduledTask task = new ScheduledTask(id, "Task " + id);
        task.setPrompt("执行调度测试");
        task.setTriggerType("interval");
        task.setIntervalInMinutes(60);
        task.setEnabled(enabled);
        return task;
    }

    private static void await(BooleanSupplier condition, Duration timeout) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertTrue(condition.getAsBoolean(), "condition was not met within " + timeout);
    }

    private static class CountingRunner implements ScheduledTaskRunner {
        private final AtomicInteger shutdowns = new AtomicInteger();

        @Override
        public void run(ScheduledRunControl control, ToolCallOrigin origin, String prompt,
                        ConversationCallbacks callbacks) {
            callbacks.onTerminal(ConversationOutcome.completed());
        }

        @Override
        public void shutdown() {
            shutdowns.incrementAndGet();
        }
    }

    private static final class NoTerminalRunner extends CountingRunner {
        @Override
        public void run(ScheduledRunControl control, ToolCallOrigin origin, String prompt,
                        ConversationCallbacks callbacks) {
        }
    }

    private static final class NullMessageFailureRunner extends CountingRunner {
        @Override
        public void run(ScheduledRunControl control, ToolCallOrigin origin, String prompt,
                        ConversationCallbacks callbacks) {
            throw new IllegalStateException();
        }
    }

    private static final class FaultBackend implements ScheduleBackend {
        private static final String GROUP = "javaclaw-scheduled-tasks";
        private final Map<TriggerKey, Trigger> triggers = new ConcurrentHashMap<>();
        private final AtomicInteger shutdownAttempts = new AtomicInteger();
        private boolean failClear;
        private boolean failSchedule;
        private boolean failDelete;
        private boolean failGet;
        private boolean failShutdown;
        private boolean forceNullNextFire;

        @Override
        public void clear() throws SchedulerException {
            if (failClear) throw new SchedulerException("clear failed");
            triggers.clear();
        }

        @Override
        public void scheduleJob(JobDetail job, Trigger trigger) throws SchedulerException {
            if (failSchedule) throw new SchedulerException("schedule failed");
            if (trigger instanceof OperableTrigger operable) {
                operable.setNextFireTime(trigger.getStartTime());
            }
            triggers.put(trigger.getKey(), trigger);
        }

        @Override
        public boolean deleteJob(JobKey key) throws SchedulerException {
            if (failDelete) throw new SchedulerException("delete failed");
            return triggers.remove(TriggerKey.triggerKey(key.getName(), key.getGroup())) != null;
        }

        @Override
        public Trigger getTrigger(TriggerKey key) throws SchedulerException {
            if (failGet) throw new SchedulerException("get failed");
            Trigger trigger = triggers.get(key);
            if (forceNullNextFire && trigger instanceof OperableTrigger operable) {
                operable.setNextFireTime(null);
            }
            return trigger;
        }

        @Override
        public void shutdown(boolean waitForJobsToComplete) throws SchedulerException {
            shutdownAttempts.incrementAndGet();
            if (failShutdown) throw new SchedulerException("shutdown failed");
            triggers.clear();
        }

        Trigger trigger(String id) {
            return triggers.get(TriggerKey.triggerKey(id, GROUP));
        }
    }
}
