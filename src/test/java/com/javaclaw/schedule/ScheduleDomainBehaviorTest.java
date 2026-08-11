package com.javaclaw.schedule;

import com.javaclaw.api.conversation.CancellationReason;
import com.javaclaw.browser.PlaywrightBrowserManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.quartz.CronTrigger;
import org.quartz.SimpleTrigger;
import org.quartz.Trigger;
import reactor.core.Disposable;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScheduleDomainBehaviorTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void scheduledTaskNormalizesIntervalsDescribesTriggersAndBoundsHistory() {
        ScheduledTask task = new ScheduledTask("task", "任务");
        assertEquals("每 60 分钟", task.describeTrigger());
        task.setTriggerSummary("事件触发");
        assertEquals("事件触发", task.describeTrigger());
        task.setTriggerSummary(" ");

        task.setTriggerType("once");
        task.setOnceDateTime(null);
        assertEquals("一次性 未设置", task.describeTrigger());
        task.setOnceDateTime(" ");
        assertEquals("一次性 未设置", task.describeTrigger());
        task.setOnceDateTime("2030-01-02 03:04");
        assertEquals("一次性 2030-01-02 03:04", task.describeTrigger());

        task.setTriggerType("daily");
        task.setDailyTime(null);
        assertEquals("每天 09:00", task.describeTrigger());
        task.setDailyTime(" ");
        assertEquals("每天 09:00", task.describeTrigger());
        task.setDailyTime("18:45");
        assertEquals("每天 18:45", task.describeTrigger());

        task.setTriggerType("cron");
        task.setCronExpression(null);
        assertEquals("Cron ", task.describeTrigger());
        task.setCronExpression("0 0 9 * * ?");
        assertEquals("Cron 0 0 9 * * ?", task.describeTrigger());

        task.setTriggerType(null);
        task.setIntervalValue(0);
        task.setIntervalMinutes(0);
        task.setIntervalUnit(null);
        assertEquals("每 1 分钟", task.describeTrigger());
        task.setIntervalValue(2);
        task.setIntervalUnit("hour");
        assertEquals("每 2 小时", task.describeTrigger());
        task.setIntervalUnit("day");
        assertEquals("每 2 天", task.describeTrigger());
        task.setIntervalUnit("other");
        assertEquals("每 2 分钟", task.describeTrigger());

        task.setIntervalValue(0);
        task.setIntervalUnit(null);
        task.recomputeIntervalMinutes();
        assertEquals(1, task.getIntervalMinutes());
        task.setIntervalValue(2);
        task.setIntervalUnit("hour");
        task.recomputeIntervalMinutes();
        assertEquals(120, task.getIntervalMinutes());
        task.setIntervalUnit("day");
        task.recomputeIntervalMinutes();
        assertEquals(2_880, task.getIntervalMinutes());
        task.setIntervalUnit("minute");
        task.recomputeIntervalMinutes();
        assertEquals(2, task.getIntervalMinutes());

        task.setTriggerType("daily");
        task.setIntervalMinutes(7);
        task.normalizeIntervalFields();
        assertEquals(7, task.getIntervalMinutes());
        task.setTriggerType("interval");
        task.setIntervalMinutes(120);
        task.setIntervalValue(2);
        task.setIntervalUnit("hour");
        task.normalizeIntervalFields();
        assertEquals(2, task.getIntervalValue());
        task.setIntervalMinutes(0);
        task.setIntervalValue(3);
        task.setIntervalUnit("day");
        task.normalizeIntervalFields();
        assertEquals(1, task.getIntervalMinutes());
        assertEquals(1, task.getIntervalValue());
        assertEquals("minute", task.getIntervalUnit());
        task.setIntervalInMinutes(0);
        assertEquals(1, task.getIntervalMinutes());
        task.setIntervalInMinutes(30);
        assertEquals(30, task.getIntervalValue());

        task.setExecutionHistory(null);
        task.setExecRecords(null);
        for (int i = 0; i < 25; i++) {
            task.addExecutionRecord("history-" + i);
            task.addExecRecord(new ScheduledTask.ExecRecord(
                    "time-" + i, "status-" + i, "duration-" + i, "note-" + i));
        }
        assertEquals(20, task.getExecutionHistory().size());
        assertEquals("history-24", task.getExecutionHistory().getFirst());
        assertEquals(20, task.getExecRecords().size());
        assertEquals("note-24", task.getExecRecords().getFirst().getNote());

        task.recordExecution(true);
        task.recordExecution(false);
        task.recordCancellation();
        assertEquals(3, task.getRunCount());
        assertEquals(1, task.getFailCount());
        assertEquals("已取消", task.getLastRunStatus());
        assertFalse(task.getLastRunTime().isBlank());
    }

    @Test
    void taskCopiesAreDeepAndNullCollectionsBecomeEmpty() {
        ScheduledTask source = new ScheduledTask("copy", "复制");
        source.setDescription("描述");
        source.setPrompt("提示");
        source.setEnabled(true);
        source.setVersion(8);
        source.setNotifyEnabled(true);
        source.setNotifyChannel("all");
        source.setUnattendedToolsAuthorized(true);
        source.setBuiltin(true);
        source.setTriggerSummary("摘要");
        source.setSourceModule("module");
        source.setExecutionHistory(new ArrayList<>(List.of("history")));
        source.setExecRecords(new ArrayList<>(List.of(
                new ScheduledTask.ExecRecord("time", "success", "1ms", "note"))));

        ScheduledTask copy = source.copy();
        assertNotEquals(source, copy);
        assertEquals(source.getId(), copy.getId());
        assertEquals("history", copy.getExecutionHistory().getFirst());
        assertEquals("note", copy.getExecRecords().getFirst().getNote());
        source.getExecutionHistory().set(0, "changed");
        source.getExecRecords().getFirst().setNote("changed");
        assertEquals("history", copy.getExecutionHistory().getFirst());
        assertEquals("note", copy.getExecRecords().getFirst().getNote());

        source.setExecutionHistory(null);
        source.setExecRecords(null);
        ScheduledTask nullSafeCopy = source.copy();
        assertTrue(nullSafeCopy.getExecutionHistory().isEmpty());
        assertTrue(nullSafeCopy.getExecRecords().isEmpty());

        ScheduledTask.ExecRecord record = new ScheduledTask.ExecRecord();
        record.setTime("time");
        record.setStatus("status");
        record.setDuration("duration");
        record.setNote("note");
        assertEquals("time", record.getTime());
        assertEquals("status", record.getStatus());
        assertEquals("duration", record.getDuration());
        assertEquals("note", record.getNote());
    }

    @Test
    void triggerFactoryRejectsInvalidDefinitionsAndBuildsQuartzMisfirePolicies() {
        ScheduleTriggerFactory factory = new ScheduleTriggerFactory("test-group");

        ScheduledTask interval = task("interval", "interval");
        interval.setIntervalMinutes(0);
        SimpleTrigger defaultInterval = (SimpleTrigger) factory.create(interval);
        assertEquals(60L * 60_000L, defaultInterval.getRepeatInterval());
        interval.setIntervalMinutes(5);
        SimpleTrigger fiveMinutes = (SimpleTrigger) factory.create(interval);
        assertEquals(5L * 60_000L, fiveMinutes.getRepeatInterval());

        ScheduledTask once = task("once", "once");
        once.setOnceDateTime(null);
        assertNull(factory.create(once));
        once.setOnceDateTime(" ");
        assertNull(factory.create(once));
        once.setOnceDateTime("invalid");
        assertNull(factory.create(once));
        once.setOnceDateTime("2020-01-01 00:00");
        Trigger catchUp = factory.create(once);
        assertNotNull(catchUp);
        assertTrue(catchUp.getStartTime().getTime() <= System.currentTimeMillis() + 1_000);
        once.setOnceDateTime(LocalDateTime.now().plusDays(2)
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")));
        assertTrue(factory.create(once).getStartTime().after(new java.util.Date()));

        ScheduledTask daily = task("daily", "daily");
        daily.setDailyTime(null);
        CronTrigger defaultDaily = (CronTrigger) factory.create(daily);
        assertEquals("0 0 9 * * ?", defaultDaily.getCronExpression());
        daily.setDailyTime(" ");
        assertNotNull(factory.create(daily));
        daily.setDailyTime("18:45");
        assertEquals("0 45 18 * * ?", ((CronTrigger) factory.create(daily)).getCronExpression());
        daily.setDailyTime("99:99");
        assertNull(factory.create(daily));

        ScheduledTask cron = task("cron", "cron");
        cron.setCronExpression(null);
        assertNull(factory.create(cron));
        cron.setCronExpression(" ");
        assertNull(factory.create(cron));
        cron.setCronExpression("0 9 * * *");
        assertNull(factory.create(cron));
        cron.setCronExpression("0 0 9 * * ?");
        assertNotNull(factory.create(cron));

        ScheduledTask unknown = task("unknown", "something-new");
        assertTrue(factory.create(unknown) instanceof SimpleTrigger);
    }

    @Test
    void builtinRegistryKeepsReadOnlySnapshotsAndRegistrationOwnership() throws Exception {
        BuiltinScheduleRegistry registry = new BuiltinScheduleRegistry();
        assertFalse(registry.contains(null));
        assertFalse(registry.contains("user-task"));
        assertTrue(registry.contains("sys:anything"));
        assertNull(registry.find(null));
        assertNull(registry.find("missing"));
        assertNull(registry.snapshot("missing"));
        assertEquals(2, registry.snapshots().size());

        ScheduledTask cleanup = registry.snapshot("sys:cmd-session-cleanup");
        assertNotNull(cleanup);
        assertTrue(cleanup.isBuiltin());
        cleanup.setName("mutated snapshot");
        assertEquals("命令会话清理", registry.snapshot(cleanup.getId()).getName());
        assertFalse(registry.hasAction(cleanup.getId()));
        assertNull(registry.action(cleanup.getId()));

        assertThrows(NullPointerException.class, () -> registry.register(null, () -> "x"));
        assertThrows(NullPointerException.class, () -> registry.register(cleanup.getId(), null));
        assertThrows(IllegalArgumentException.class,
                () -> registry.register("sys:missing", () -> "x"));
        ScheduleManager.BuiltinRunner first = () -> "first";
        ScheduleManager.BuiltinRunner second = () -> "second";
        AutoCloseable firstHandle = registry.register(cleanup.getId(), first);
        assertTrue(registry.hasAction(cleanup.getId()));
        assertSame(first, registry.action(cleanup.getId()));
        AutoCloseable secondHandle = registry.register(cleanup.getId(), second);
        firstHandle.close();
        assertSame(second, registry.action(cleanup.getId()));
        secondHandle.close();
        assertFalse(registry.hasAction(cleanup.getId()));

        assertFalse(registry.record("missing", true, 10, "note"));
        assertTrue(registry.record(cleanup.getId(), true, 0, null));
        assertTrue(registry.record(cleanup.getId(), false, 850, " "));
        assertTrue(registry.record(cleanup.getId(), false, 1_250, "x".repeat(80)));
        ScheduledTask recorded = registry.snapshot(cleanup.getId());
        assertEquals(3, recorded.getRunCount());
        assertEquals(2, recorded.getFailCount());
        assertEquals("1.3s", recorded.getLastDuration());
        assertEquals(61, recorded.getExecRecords().getFirst().getNote().length());
    }

    @Test
    void runControlPropagatesCancellationRegardlessOfAttachmentOrder() throws Exception {
        assertThrows(NullPointerException.class, () -> new ScheduledRunControl(null));
        ScheduledRunControl control = new ScheduledRunControl("task");
        assertFalse(control.runId().isBlank());
        assertEquals("task", control.taskId());
        assertFalse(control.isCancelled());
        assertFalse(control.hasStarted());
        assertEquals(CancellationReason.SCHEDULE_DISABLED, control.cancellationReason());
        assertThrows(NullPointerException.class, () -> control.cancel(null));

        AtomicInteger queuedCancels = new AtomicInteger();
        RecordingDisposable disposable = new RecordingDisposable();
        RecordingBrowser browser = new RecordingBrowser(temporaryDirectory, false);
        Thread worker = new Thread(() -> { }, "not-started-worker");
        control.attachQueuedCancellation(queuedCancels::incrementAndGet);
        control.attachSubscription(disposable);
        control.attachBrowser(browser);
        control.attachWorker(worker);
        assertTrue(control.cancel(CancellationReason.SHUTDOWN));
        assertFalse(control.cancel(CancellationReason.RUNTIME_REBUILD));
        assertEquals(CancellationReason.SHUTDOWN, control.cancellationReason());
        assertEquals(2, queuedCancels.get());
        assertTrue(disposable.isDisposed());
        assertEquals(2, browser.shutdownCalls.get());
        assertTrue(worker.isInterrupted());

        AtomicInteger lateQueuedCancels = new AtomicInteger();
        RecordingDisposable lateDisposable = new RecordingDisposable();
        RecordingBrowser throwingBrowser = new RecordingBrowser(temporaryDirectory, true);
        Thread lateWorker = new Thread(() -> { }, "late-worker");
        control.attachQueuedCancellation(lateQueuedCancels::incrementAndGet);
        control.attachSubscription(lateDisposable);
        control.attachBrowser(throwingBrowser);
        control.attachWorker(lateWorker);
        assertEquals(1, lateQueuedCancels.get());
        assertTrue(lateDisposable.isDisposed());
        assertEquals(1, throwingBrowser.shutdownCalls.get());
        assertTrue(lateWorker.isInterrupted());
        control.clearSubscription(new RecordingDisposable());
        control.clearSubscription(lateDisposable);
        control.clearBrowser(browser);
        control.clearBrowser(throwingBrowser);
        control.detachWorker();

        ScheduledRunControl started = new ScheduledRunControl("started");
        AtomicInteger startedQueueCancels = new AtomicInteger();
        started.attachQueuedCancellation(startedQueueCancels::incrementAndGet);
        assertTrue(started.markStarted());
        assertFalse(started.markStarted());
        started.attachWorker(Thread.currentThread());
        assertTrue(started.cancel(CancellationReason.SCHEDULE_DISABLED));
        assertEquals(0, startedQueueCancels.get());
        assertFalse(Thread.currentThread().isInterrupted());
    }

    private static ScheduledTask task(String id, String triggerType) {
        ScheduledTask task = new ScheduledTask(id, "Task " + id);
        task.setTriggerType(triggerType);
        return task;
    }

    private static final class RecordingDisposable implements Disposable {
        private final AtomicBoolean disposed = new AtomicBoolean();

        @Override
        public void dispose() {
            disposed.set(true);
        }

        @Override
        public boolean isDisposed() {
            return disposed.get();
        }
    }

    private static final class RecordingBrowser extends PlaywrightBrowserManager {
        private final AtomicInteger shutdownCalls = new AtomicInteger();
        private final boolean fail;

        private RecordingBrowser(Path root, boolean fail) {
            super(true, root.resolve("browser"), root.resolve("screenshots"));
            this.fail = fail;
        }

        @Override
        public synchronized void shutdown() {
            shutdownCalls.incrementAndGet();
            if (fail) throw new IllegalStateException("simulated close failure");
        }
    }
}
