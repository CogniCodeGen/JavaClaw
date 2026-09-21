package com.javaclaw.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.javaclaw.config.FileDatabaseAccess;
import com.javaclaw.workflow.editor.WorkflowEditorModel;
import com.javaclaw.workflow.model.GraphState;
import com.javaclaw.workflow.model.StatePatch;
import com.javaclaw.workflow.model.RunStatus;
import com.javaclaw.workflow.runtime.CheckpointPhase;
import com.javaclaw.workflow.runtime.GraphRun;
import com.javaclaw.workflow.store.H2GraphCheckpointStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class GraphThreadDeletionTest {
    @TempDir Path directory;
    private final ObjectMapper json = new ObjectMapper();

    @Test void deletingGraphThreadPurgesOnlyItsOwnHistoryAndRejectsAllLateWriters() throws Exception {
        var database = new FileDatabaseAccess(directory);
        var store = new H2GraphCheckpointStore("workspace", database, json);
        var otherWorkspace = new H2GraphCheckpointStore("other", database, json);
        var removed = run("thread"); var retained = run("thread-other");
        store.createRun(removed); store.createRun(retained); otherWorkspace.createRun(removed);
        store.checkpoint(removed, "start", CheckpointPhase.BEFORE_NODE);
        store.saveThreadState(removed.workflowId(), "thread", state("private"));
        otherWorkspace.saveThreadState(removed.workflowId(), "thread", state("other workspace"));
        store.deleteThread("thread"); store.deleteThread("thread");
        assertNull(store.loadRun(removed.id()));
        assertNotNull(store.loadRun(retained.id())); assertNotNull(otherWorkspace.loadRun(removed.id()));
        assertEquals(1, store.listRuns(null, 20).size());
        assertTrue(store.loadThreadState(removed.workflowId(), "thread").get("value").isMissingNode());
        assertEquals("other workspace", otherWorkspace.loadThreadState(removed.workflowId(), "thread").get("value").asText());
        assertThrows(IllegalStateException.class, () -> store.createRun(run("thread")));
        assertThrows(IllegalStateException.class, () -> store.updateRun(removed));
        assertThrows(IllegalStateException.class, () -> store.checkpoint(removed, "late", CheckpointPhase.BEFORE_NODE));
        assertThrows(IllegalStateException.class, () -> store.saveThreadState(removed.workflowId(), "thread", state("late")));
        assertThrows(IllegalStateException.class, () -> new H2GraphCheckpointStore("workspace", database, json)
                .saveThreadState(removed.workflowId(), "thread", state("after restart")));
        try (var connection = database.open(); var query = connection.prepareStatement(
                "SELECT COUNT(*) FROM workflow_checkpoints WHERE workspace_id=? AND run_id=?")) {
            query.setString(1, "workspace"); query.setString(2, removed.id());
            try (var rows = query.executeQuery()) { assertTrue(rows.next()); assertEquals(0, rows.getInt(1)); }
        }
    }

    @Test void agentTombstoneImmediatelyBlocksGraphReadsWritesAndRecoveryScanning() throws Exception {
        var database = new FileDatabaseAccess(directory);
        var store = new H2GraphCheckpointStore("workspace", database, json);
        var run = run("deleted-agent"); store.createRun(run);
        try (var connection = database.open(); var statement = connection.prepareStatement("""
                INSERT INTO agent_threads(workspace_id,user_id,thread_id,title,status,configuration_json,created_at,updated_at)
                VALUES('workspace','local-user','deleted-agent','','DELETING','{}',0,0)
                """)) { statement.executeUpdate(); }
        assertNull(store.loadRun(run.id()));
        assertTrue(store.listRuns(null, 20).isEmpty()); assertTrue(store.listNonTerminalRuns().isEmpty());
        assertEquals(0, store.markRunningAsRecoveryRequired());
        assertThrows(IllegalStateException.class, () -> store.checkpoint(run, "late", CheckpointPhase.BEFORE_NODE));
        assertThrows(IllegalStateException.class, () -> store.saveThreadState(run.workflowId(), run.threadId(), state("late")));
        store.deleteThread(run.threadId());
    }

    @Test void pendingLegacyWriterCannotCrossACommittedDeletionFence() throws Exception {
        var database = new FileDatabaseAccess(directory);
        var store = new H2GraphCheckpointStore("workspace", database, json);
        var run = run("racing"); store.createRun(run);
        CompletableFuture<Void> writer;
        try (var connection = database.open()) {
            connection.setAutoCommit(false);
            try (var statement = connection.prepareStatement("SELECT deleted FROM workflow_thread_lifecycle WHERE workspace_id='workspace' AND thread_id='racing' FOR UPDATE");
                 var rows = statement.executeQuery()) { assertTrue(rows.next()); }
            var started = new CountDownLatch(1);
            writer = CompletableFuture.runAsync(() -> {
                started.countDown(); store.saveThreadState(run.workflowId(), "racing", state("late"));
            });
            assertTrue(started.await(2, TimeUnit.SECONDS));
            assertThrows(TimeoutException.class, () -> writer.get(100, TimeUnit.MILLISECONDS));
            try (var statement = connection.prepareStatement("UPDATE workflow_thread_lifecycle SET deleted=TRUE WHERE workspace_id='workspace' AND thread_id='racing'")) {
                statement.executeUpdate();
            }
            connection.commit();
        }
        assertThrows(ExecutionException.class, () -> writer.get(3, TimeUnit.SECONDS));
        store.deleteThread("racing");
        assertTrue(store.loadThreadState(run.workflowId(), "racing").get("value").isMissingNode());
    }
    private GraphRun run(String thread) { return new GraphRun(WorkflowEditorModel.blank("task"), thread, new GraphState()); }
    private GraphState state(String value) { return new GraphState().apply(StatePatch.builder().set("value", value).build()); }
}
