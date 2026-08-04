package com.javaclaw.schedule;

import com.javaclaw.config.AppDatabase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class ScheduledTaskStoreTest {

    @TempDir
    Path dataDir;

    @Test
    void staleSnapshotCannotOverwriteNewerDefinition() {
        ScheduledTaskStore store = store();
        ScheduledTask initial = store.insert("ws", task("versioned", true));
        ScheduledTask stale = initial.copy();

        ScheduledTask newer = initial.copy();
        newer.setName("新名称");
        newer = store.updateDefinition("ws", newer);

        stale.setName("旧快照覆盖");
        assertThrows(ScheduleConflictException.class,
                () -> store.updateDefinition("ws", stale));
        ScheduledTask current = store.find("ws", initial.getId());
        assertEquals("新名称", current.getName());
        assertEquals(newer.getVersion(), current.getVersion());
    }

    @Test
    void executionResultDoesNotRestoreDisabledConfiguration() {
        ScheduledTaskStore store = store();
        ScheduledTask runningSnapshot = store.insert("ws", task("race", true));

        ScheduledTask disabled = runningSnapshot.copy();
        disabled.setEnabled(false);
        disabled.setPrompt("停用后的新提示词");
        disabled = store.updateDefinition("ws", disabled);

        ScheduledTask afterRun = store.recordExecution("ws", runningSnapshot.getId(),
                new ScheduledTaskStore.ExecutionResult(
                        ScheduledTaskStore.ExecutionStatus.SUCCESS, "120ms", "done"));

        assertFalse(afterRun.isEnabled());
        assertEquals("停用后的新提示词", afterRun.getPrompt());
        assertEquals(disabled.getVersion(), afterRun.getVersion());
        assertEquals(1, afterRun.getRunCount());
        assertEquals(0, afterRun.getFailCount());
    }

    @Test
    void cancellationIsNeutralAndIntervalUsesQuartzMinutesAsSourceOfTruth() {
        ScheduledTaskStore store = store();
        ScheduledTask inconsistent = task("cancelled", true);
        inconsistent.setIntervalMinutes(15);
        inconsistent.setIntervalValue(2);
        inconsistent.setIntervalUnit("hour");

        ScheduledTask saved = store.insert("ws", inconsistent);
        assertEquals(15, saved.getIntervalMinutes());
        assertEquals(15, saved.getIntervalValue());
        assertEquals("minute", saved.getIntervalUnit());

        ScheduledTask cancelled = store.recordExecution("ws", saved.getId(),
                new ScheduledTaskStore.ExecutionResult(
                        ScheduledTaskStore.ExecutionStatus.CANCELLED, "40ms", "disabled"));
        assertEquals("已取消", cancelled.getLastRunStatus());
        assertEquals(1, cancelled.getRunCount());
        assertEquals(0, cancelled.getFailCount());
        assertEquals("已取消", cancelled.getExecRecords().getFirst().getStatus());
    }

    private ScheduledTaskStore store() {
        return new ScheduledTaskStore(() -> AppDatabase.open(dataDir));
    }

    private static ScheduledTask task(String id, boolean enabled) {
        ScheduledTask task = new ScheduledTask(id, "Task " + id);
        task.setPrompt("执行测试");
        task.setTriggerType("interval");
        task.setIntervalInMinutes(60);
        task.setEnabled(enabled);
        return task;
    }
}
