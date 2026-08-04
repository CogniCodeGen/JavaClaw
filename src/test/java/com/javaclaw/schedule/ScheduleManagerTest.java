package com.javaclaw.schedule;

import com.javaclaw.agent.ToolCallOrigin;
import com.javaclaw.api.conversation.ConversationCallbacks;
import com.javaclaw.api.conversation.ConversationOutcome;
import com.javaclaw.config.AppDatabase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.quartz.CronTrigger;
import org.quartz.JobDetail;
import org.quartz.JobKey;
import org.quartz.SchedulerException;
import org.quartz.SimpleTrigger;
import org.quartz.Trigger;
import org.quartz.TriggerKey;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

class ScheduleManagerTest {

    private static final String JOB_GROUP = "javaclaw-scheduled-tasks";

    @TempDir
    Path dataDir;

    private ScheduleManager manager;

    @AfterEach
    void tearDown() {
        if (manager != null) manager.shutdown();
    }

    @Test
    void externalDisableCancelsRunningTaskAndRecordsNeutralOutcome() throws Exception {
        ControlledRunner runner = new ControlledRunner();
        manager = manager(runner);
        ScheduledTask saved = manager.saveNewTask(task("external-stop", true));

        assertEquals(ScheduleManager.RunNowResult.STARTED,
                manager.runNow(saved.getId(), false));
        assertTrue(runner.started.await(2, TimeUnit.SECONDS));

        manager.setEnabled(saved.getId(), false, ScheduleManager.DisableMode.CANCEL_ACTIVE);

        await(() -> !manager.isActive(saved.getId()), Duration.ofSeconds(3));
        ScheduledTask stopped = manager.getTask(saved.getId());
        assertFalse(stopped.isEnabled());
        assertEquals("已取消", stopped.getLastRunStatus());
        assertEquals(1, stopped.getRunCount());
        assertEquals(0, stopped.getFailCount());
        assertTrue(runner.lastControl.isCancelled());
    }

    @Test
    void duplicateTriggerIsMergedWhileTaskIsQueuedOrRunning() throws Exception {
        ControlledRunner runner = new ControlledRunner();
        manager = manager(runner);
        ScheduledTask saved = manager.saveNewTask(task("dedupe", true));

        assertEquals(ScheduleManager.RunNowResult.STARTED,
                manager.runNow(saved.getId(), false));
        assertTrue(runner.started.await(2, TimeUnit.SECONDS));
        assertEquals(ScheduleManager.RunNowResult.ALREADY_ACTIVE,
                manager.runNow(saved.getId(), false));

        runner.release.countDown();
        await(() -> !manager.isActive(saved.getId()), Duration.ofSeconds(3));
        assertEquals(1, runner.starts.get());
        assertEquals(1, manager.getTask(saved.getId()).getRunCount());
    }

    @Test
    void selfDisableRemovesFutureScheduleButLetsCurrentRunFinish() throws Exception {
        ControlledRunner runner = new ControlledRunner();
        manager = manager(runner);
        ScheduledTask saved = manager.saveNewTask(task("self-stop", true));

        assertEquals(ScheduleManager.RunNowResult.STARTED,
                manager.runNow(saved.getId(), false));
        assertTrue(runner.started.await(2, TimeUnit.SECONDS));
        manager.setEnabled(saved.getId(), false, ScheduleManager.DisableMode.AFTER_CURRENT_RUN);
        assertFalse(runner.lastControl.isCancelled());

        runner.release.countDown();
        await(() -> !manager.isActive(saved.getId()), Duration.ofSeconds(3));
        ScheduledTask completed = manager.getTask(saved.getId());
        assertFalse(completed.isEnabled());
        assertEquals("成功", completed.getLastRunStatus());
        assertEquals(1, completed.getRunCount());
        assertEquals(0, completed.getFailCount());
    }

    @Test
    void disablingQueuedTaskPreventsRunnerFromStarting() throws Exception {
        PerTaskBlockingRunner runner = new PerTaskBlockingRunner();
        manager = manager(runner);
        ScheduledTask first = manager.saveNewTask(task("first", true));
        ScheduledTask queued = manager.saveNewTask(task("queued", true));

        assertEquals(ScheduleManager.RunNowResult.STARTED,
                manager.runNow(first.getId(), false));
        assertTrue(runner.firstStarted.await(2, TimeUnit.SECONDS));
        assertEquals(ScheduleManager.RunNowResult.STARTED,
                manager.runNow(queued.getId(), false));
        assertTrue(manager.isActive(queued.getId()));

        manager.setEnabled(queued.getId(), false, ScheduleManager.DisableMode.CANCEL_ACTIVE);
        await(() -> !manager.isActive(queued.getId()), Duration.ofSeconds(2));
        runner.releaseFirst.countDown();
        await(() -> !manager.isActive(first.getId()), Duration.ofSeconds(3));

        assertEquals(0, runner.startsByTask.getOrDefault(queued.getId(), new AtomicInteger()).get());
        assertEquals(0, manager.getTask(queued.getId()).getRunCount());
        assertFalse(manager.getTask(queued.getId()).isEnabled());
    }

    @Test
    void disabledTaskCanRunOnceWithoutBeingEnabled() throws Exception {
        ControlledRunner runner = new ControlledRunner();
        manager = manager(runner);
        ScheduledTask saved = manager.saveNewTask(task("manual-once", false));

        assertEquals(ScheduleManager.RunNowResult.DISABLED,
                manager.runNow(saved.getId(), false));
        assertEquals(ScheduleManager.RunNowResult.STARTED,
                manager.runNow(saved.getId(), true));
        assertTrue(runner.started.await(2, TimeUnit.SECONDS));
        runner.release.countDown();
        await(() -> !manager.isActive(saved.getId()), Duration.ofSeconds(3));

        ScheduledTask after = manager.getTask(saved.getId());
        assertFalse(after.isEnabled());
        assertEquals("成功", after.getLastRunStatus());
        assertEquals(1, after.getRunCount());
    }

    @Test
    void disabledTaskIsNotRegisteredAfterRestart() throws Exception {
        ScheduledTaskRunner noop = new CompletingRunner();
        FakeScheduleBackend firstQuartz = scheduler();
        ScheduleManager first = new ScheduleManager(store(), "ws", firstQuartz,
                daemonExecutor(), noop);
        ScheduledTask saved;
        try {
            first.init(noop);
            saved = first.saveNewTask(task("restart-disabled", false));
        } finally {
            first.shutdown();
        }

        FakeScheduleBackend restartedQuartz = scheduler();
        manager = new ScheduleManager(store(), "ws", restartedQuartz,
                daemonExecutor(), noop);
        manager.init(noop);

        assertFalse(restartedQuartz.hasJob(JobKey.jobKey(saved.getId(), JOB_GROUP)));
        assertFalse(manager.getTask(saved.getId()).isEnabled());
    }

    @Test
    void optimisticConflictRefreshesManagersInMemorySnapshot() throws Exception {
        ScheduledTaskRunner noop = new CompletingRunner();
        ScheduleManager first = new ScheduleManager(store(), "ws", scheduler(),
                daemonExecutor(), noop);
        ScheduleManager second = null;
        try {
            first.init(noop);
            ScheduledTask saved = first.saveNewTask(task("conflict-refresh", false));
            // 第二个管理器模拟在旧快照上工作的窗口/进程。
            second = new ScheduleManager(store(), "ws", scheduler(), daemonExecutor(), noop);
            second.init(noop);
            ScheduleManager staleManager = second;
            ScheduledTask stale = staleManager.getTask(saved.getId());

            ScheduledTask fresh = first.getTask(saved.getId());
            fresh.setName("已由其他窗口更新");
            first.updateTask(fresh);

            stale.setName("过期修改");
            assertThrows(ScheduleConflictException.class, () -> staleManager.updateTask(stale));
            assertEquals("已由其他窗口更新", staleManager.getTask(saved.getId()).getName());
        } finally {
            first.shutdown();
            if (second != null) second.shutdown();
        }
    }

    @Test
    void recurringTriggersSkipMisfiresAndExpiredOnceTaskCatchesUpOnce() throws Exception {
        CompletingRunner runner = new CompletingRunner();
        FakeScheduleBackend backend = scheduler();
        manager = new ScheduleManager(store(), "ws", backend, daemonExecutor(), runner);
        manager.init(runner);

        ScheduledTask interval = manager.saveNewTask(task("misfire-interval", true));
        ScheduledTask daily = task("misfire-daily", true);
        daily.setTriggerType("daily");
        daily.setDailyTime("09:30");
        daily = manager.saveNewTask(daily);
        ScheduledTask cron = task("misfire-cron", true);
        cron.setTriggerType("cron");
        cron.setCronExpression("0 0/10 * * * ?");
        cron = manager.saveNewTask(cron);
        ScheduledTask once = task("misfire-once", true);
        once.setTriggerType("once");
        once.setOnceDateTime("2020-01-01 00:00");
        once = manager.saveNewTask(once);

        SimpleTrigger intervalTrigger = (SimpleTrigger) backend.trigger(interval.getId());
        CronTrigger dailyTrigger = (CronTrigger) backend.trigger(daily.getId());
        CronTrigger cronTrigger = (CronTrigger) backend.trigger(cron.getId());
        SimpleTrigger onceTrigger = (SimpleTrigger) backend.trigger(once.getId());
        assertEquals(SimpleTrigger.MISFIRE_INSTRUCTION_RESCHEDULE_NEXT_WITH_REMAINING_COUNT,
                intervalTrigger.getMisfireInstruction());
        assertEquals(CronTrigger.MISFIRE_INSTRUCTION_DO_NOTHING,
                dailyTrigger.getMisfireInstruction());
        assertEquals(CronTrigger.MISFIRE_INSTRUCTION_DO_NOTHING,
                cronTrigger.getMisfireInstruction());
        assertEquals(SimpleTrigger.MISFIRE_INSTRUCTION_FIRE_NOW,
                onceTrigger.getMisfireInstruction());
        assertTrue(onceTrigger.getStartTime().getTime() <= System.currentTimeMillis() + 1000);
    }

    @Test
    void schedulingFailureIsReportedAndTaskRemainsDisabled() {
        CompletingRunner runner = new CompletingRunner();
        FakeScheduleBackend backend = scheduler();
        backend.failSchedule = true;
        manager = new ScheduleManager(store(), "ws", backend, daemonExecutor(), runner);
        manager.init(runner);

        ScheduledTask candidate = task("schedule-failure", true);
        SchedulePersistenceException failure = assertThrows(SchedulePersistenceException.class,
                () -> manager.saveNewTask(candidate));
        assertTrue(failure.getMessage().contains("已自动保持暂停"));
        ScheduledTask saved = manager.getTask(candidate.getId());
        assertNotNull(saved);
        assertFalse(saved.isEnabled());
    }

    @Test
    void runnerExceptionIsPersistedAsFailureInsteadOfBeingLostInFuture() throws Exception {
        ScheduledTaskRunner throwing = new ScheduledTaskRunner() {
            @Override
            public void run(ScheduledRunControl control, ToolCallOrigin origin, String prompt,
                            ConversationCallbacks callbacks) {
                throw new IllegalStateException("runner exploded");
            }

            @Override
            public void shutdown() { }
        };
        manager = manager(throwing);
        ScheduledTask saved = manager.saveNewTask(task("runner-failure", true));

        assertEquals(ScheduleManager.RunNowResult.STARTED,
                manager.runNow(saved.getId(), false));
        await(() -> !manager.isActive(saved.getId()), Duration.ofSeconds(3));

        ScheduledTask failed = manager.getTask(saved.getId());
        assertEquals("失败", failed.getLastRunStatus());
        assertEquals(1, failed.getRunCount());
        assertEquals(1, failed.getFailCount());
    }

    private ScheduleManager manager(ScheduledTaskRunner runner) throws Exception {
        ScheduleManager result = new ScheduleManager(store(), "ws", scheduler(),
                daemonExecutor(), runner);
        result.init(runner);
        return result;
    }

    private ScheduledTaskStore store() {
        return new ScheduledTaskStore(() -> AppDatabase.open(dataDir));
    }

    private static java.util.concurrent.ExecutorService daemonExecutor() {
        return Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(r, "schedule-manager-test");
            thread.setDaemon(true);
            return thread;
        });
    }

    private static FakeScheduleBackend scheduler() {
        return new FakeScheduleBackend();
    }

    private static ScheduledTask task(String id, boolean enabled) {
        ScheduledTask task = new ScheduledTask(id, "Task " + id);
        task.setPrompt("运行一次测试");
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

    private static final class ControlledRunner implements ScheduledTaskRunner {
        final CountDownLatch started = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final AtomicInteger starts = new AtomicInteger();
        volatile ScheduledRunControl lastControl;

        @Override
        public void run(ScheduledRunControl control, ToolCallOrigin origin, String prompt,
                        ConversationCallbacks callbacks) {
            lastControl = control;
            starts.incrementAndGet();
            started.countDown();
            try {
                while (!control.isCancelled() && !release.await(25, TimeUnit.MILLISECONDS)) {
                    // 受控等待，便于测试停用/去重竞态。
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            // 故意无条件上报 completed：管理器必须在外部停用后
            // 将迟到的“成功”终态强制收敛为“已取消”。
            callbacks.onTerminal(ConversationOutcome.completed());
        }

        @Override
        public void shutdown() {
            release.countDown();
        }
    }

    private static final class PerTaskBlockingRunner implements ScheduledTaskRunner {
        final Map<String, AtomicInteger> startsByTask = new ConcurrentHashMap<>();
        final CountDownLatch firstStarted = new CountDownLatch(1);
        final CountDownLatch releaseFirst = new CountDownLatch(1);

        @Override
        public void run(ScheduledRunControl control, ToolCallOrigin origin, String prompt,
                        ConversationCallbacks callbacks) {
            startsByTask.computeIfAbsent(control.taskId(), ignored -> new AtomicInteger())
                    .incrementAndGet();
            firstStarted.countDown();
            try {
                releaseFirst.await();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            callbacks.onTerminal(control.isCancelled()
                    ? ConversationOutcome.cancelled(control.cancellationReason())
                    : ConversationOutcome.completed());
        }

        @Override
        public void shutdown() {
            releaseFirst.countDown();
        }
    }

    private static final class CompletingRunner implements ScheduledTaskRunner {
        @Override
        public void run(ScheduledRunControl control, ToolCallOrigin origin, String prompt,
                        ConversationCallbacks callbacks) {
            callbacks.onTerminal(ConversationOutcome.completed());
        }

        @Override
        public void shutdown() { }
    }

    private static final class FakeScheduleBackend implements ScheduleBackend {
        private final Map<TriggerKey, Trigger> triggers = new ConcurrentHashMap<>();
        private volatile boolean failSchedule;

        @Override
        public void clear() {
            triggers.clear();
        }

        @Override
        public void scheduleJob(JobDetail job, Trigger trigger) throws SchedulerException {
            if (failSchedule) throw new SchedulerException("simulated schedule failure");
            triggers.put(trigger.getKey(), trigger);
        }

        @Override
        public boolean deleteJob(JobKey key) {
            return triggers.remove(TriggerKey.triggerKey(key.getName(), key.getGroup())) != null;
        }

        @Override
        public Trigger getTrigger(TriggerKey key) {
            return triggers.get(key);
        }

        @Override
        public void shutdown(boolean waitForJobsToComplete) {
            clear();
        }

        boolean hasJob(JobKey key) {
            return triggers.containsKey(TriggerKey.triggerKey(key.getName(), key.getGroup()));
        }

        Trigger trigger(String id) {
            return triggers.get(TriggerKey.triggerKey(id, JOB_GROUP));
        }
    }
}
