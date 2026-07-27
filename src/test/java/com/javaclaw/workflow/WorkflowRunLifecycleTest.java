package com.javaclaw.workflow;

import com.javaclaw.config.FileDatabaseAccess;
import com.javaclaw.workflow.editor.WorkflowEditorModel;
import com.javaclaw.workflow.model.GraphState;
import com.javaclaw.workflow.model.RunStatus;
import com.javaclaw.workflow.node.PublicNodeCatalog;
import com.javaclaw.workflow.runtime.CheckpointPhase;
import com.javaclaw.workflow.runtime.GraphExecutionManager;
import com.javaclaw.workflow.runtime.GraphRun;
import com.javaclaw.workflow.store.H2GraphCheckpointStore;
import com.javaclaw.workflow.store.WorkflowRunMissingException;
import com.javaclaw.workflow.store.WorkflowRunStateException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class WorkflowRunLifecycleTest {

    @TempDir Path temp;

    @Test
    void 父运行缺失时更新与检查点都明确失败() {
        var store = new H2GraphCheckpointStore("ws", new FileDatabaseAccess(temp));
        GraphRun missing = runWithStatus("missing", RunStatus.RUNNING);

        assertThrows(WorkflowRunMissingException.class, () -> store.updateRun(missing));
        assertThrows(WorkflowRunMissingException.class,
                () -> store.checkpoint(missing, "start", CheckpointPhase.BEFORE_NODE));
        assertNull(store.loadRun(missing.id()));
    }

    @Test
    void 恢复必须匹配预期旧状态() {
        var store = new H2GraphCheckpointStore("ws", new FileDatabaseAccess(temp));
        GraphRun paused = runWithStatus("paused", RunStatus.PAUSED);
        store.createRun(paused);
        GraphRun run = runWithStatus(paused.id(), RunStatus.RUNNING);

        assertThrows(WorkflowRunStateException.class,
                () -> store.activateExistingRun(run, RunStatus.RECOVERY_REQUIRED));
        assertEquals(RunStatus.PAUSED, store.loadRun(run.id()).status());
    }

    @Test
    void 执行线程提交失败会把已创建运行置为失败() {
        var store = new H2GraphCheckpointStore("ws", new FileDatabaseAccess(temp));
        var rejecting = new RejectingExecutor();
        var graph = validGraph();
        try (var executions = new GraphExecutionManager(
                PublicNodeCatalog.createRegistry(), store, rejecting)) {
            assertThrows(RejectedExecutionException.class, () ->
                    executions.start(graph, "thread", new GraphState(), event -> {}, Map.of()));
        }
        var runs = store.listRuns(graph.id(), 10);
        assertEquals(1, runs.size());
        assertEquals(RunStatus.FAILED, runs.getFirst().status());
        assertTrue(runs.getFirst().error().contains("线程提交失败"));
    }

    private static com.javaclaw.workflow.model.GraphDefinition validGraph() {
        return WorkflowEditorModel.blank("生命周期测试");
    }

    private static GraphRun runWithStatus(String id, RunStatus status) {
        var graph = validGraph();
        long now = System.currentTimeMillis();
        return new GraphRun(id, graph.id(), graph.version(), "thread", graph,
                new GraphState(), status, null, graph.startNodeId(), 0, 0,
                null, null, null, now, now);
    }

    private static final class RejectingExecutor extends AbstractExecutorService {
        private boolean shutdown;
        @Override public void shutdown() { shutdown = true; }
        @Override public java.util.List<Runnable> shutdownNow() {
            shutdown = true;
            return java.util.List.of();
        }
        @Override public boolean isShutdown() { return shutdown; }
        @Override public boolean isTerminated() { return shutdown; }
        @Override public boolean awaitTermination(long timeout, TimeUnit unit) { return shutdown; }
        @Override public void execute(Runnable command) {
            throw new RejectedExecutionException("test rejection");
        }
    }
}
