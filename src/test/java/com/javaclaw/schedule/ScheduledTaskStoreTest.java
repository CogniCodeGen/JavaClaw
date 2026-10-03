package com.javaclaw.schedule;

import com.javaclaw.config.FileDatabaseAccess;
import com.javaclaw.framework.api.TaskOutcome;
import com.javaclaw.framework.api.TaskResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ScheduledTaskStoreTest {

    @Test
    void executionPolicyPersistsAcrossDefinitionAndResultWrites() {
        ScheduledTaskStore store = store();
        ScheduledTask draft = task("policy", true);
        assertEquals(ExecutionPolicy.RECURRING, draft.getExecutionPolicy());
        draft.setExecutionPolicy(ExecutionPolicy.UNTIL_CONDITION);
        ScheduledTask saved = store.insert("ws", draft);
        assertEquals(ExecutionPolicy.UNTIL_CONDITION, store.find("ws", saved.getId()).getExecutionPolicy());

        ScheduledTask updated = saved.copy();
        updated.setExecutionPolicy(ExecutionPolicy.RECURRING);
        store.updateDefinition("ws", updated);
        store.recordExecution("ws", saved.getId(), new ScheduledTaskStore.ExecutionResult(
                ScheduledTaskStore.ExecutionStatus.SUCCESS, "1s", "完成"));
        assertEquals(ExecutionPolicy.RECURRING, store.find("ws", saved.getId()).getExecutionPolicy());
    }

    @Test
    void scheduledExecutionPersistsTechnicalCompletionAndSeparateTaskOutcome() {
        ScheduledTaskStore store = store();
        ScheduledTask task = store.insert("ws", task("typed-result", true));
        TaskResult acceptance = new TaskResult(TaskOutcome.PARTIAL,
                List.of("日程观察"), "观察失败", List.of("receipt:launch"), List.of("启动日历"));
        store.recordExecution("ws", task.getId(), new ScheduledTaskStore.ExecutionResult(
                ScheduledTaskStore.ExecutionStatus.SUCCESS, "1s", "运行完成", acceptance));

        ScheduledTask reloaded = store.find("ws", task.getId());
        assertEquals("SUCCESS", reloaded.getLastRunStatus());
        assertEquals("SUCCESS", reloaded.getExecRecords().getFirst().getStatus());
        assertEquals(acceptance, reloaded.getExecRecords().getFirst().getTaskResult());
    }

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
    void executionResultDoesNotRestoreDisabledConfiguration() throws Exception {
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
        ScheduledTask reloaded = store.find("ws", runningSnapshot.getId());
        assertEquals(1, reloaded.getExecRecords().size());
        assertEquals("done", reloaded.getExecRecords().getFirst().getNote());
        try (var connection = new FileDatabaseAccess(dataDir).open();
             var query = connection.prepareStatement(
                     "SELECT exec_records_json FROM scheduled_tasks WHERE workspace_id = ? AND id = ?")) {
            query.setString(1, "ws");
            query.setString(2, runningSnapshot.getId());
            try (var rows = query.executeQuery()) {
                assertTrue(rows.next());
                assertNotNull(rows.getString(1));
                assertTrue(rows.getString(1).contains("done"));
            }
        }
    }

    @Test
    void cancellationIsNeutralAndInconsistentIntervalIsRejected() {
        ScheduledTaskStore store = store();
        ScheduledTask inconsistent = task("cancelled", true);
        inconsistent.setIntervalMinutes(15);
        inconsistent.setIntervalValue(2);
        inconsistent.setIntervalUnit("hour");

        assertThrows(IllegalArgumentException.class, () -> store.insert("ws", inconsistent));
        ScheduledTask saved = store.insert("ws", task("cancelled", true));

        ScheduledTask cancelled = store.recordExecution("ws", saved.getId(),
                new ScheduledTaskStore.ExecutionResult(
                        ScheduledTaskStore.ExecutionStatus.CANCELLED, "40ms", "disabled"));
        assertEquals("CANCELLED", cancelled.getLastRunStatus());
        assertEquals(1, cancelled.getRunCount());
        assertEquals(0, cancelled.getFailCount());
        assertEquals("CANCELLED", cancelled.getExecRecords().getFirst().getStatus());
    }

    @Test
    void malformedExecutionHistoryIsRejectedInsteadOfDiscarded() throws Exception {
        ScheduledTaskStore store = store();
        ScheduledTask saved = store.insert("ws", task("history", true));
        try (var connection = new FileDatabaseAccess(dataDir).open();
             var update = connection.prepareStatement(
                     "UPDATE scheduled_tasks SET exec_records_json = ? WHERE workspace_id = ? AND id = ?")) {
            update.setString(1, "broken-json");
            update.setString(2, "ws");
            update.setString(3, saved.getId());
            update.executeUpdate();
        }
        assertThrows(SchedulePersistenceException.class,
                () -> store.find("ws", saved.getId()));
    }

    private ScheduledTaskStore store() {
        return ScheduleTestStoreFactory.create(dataDir);
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
