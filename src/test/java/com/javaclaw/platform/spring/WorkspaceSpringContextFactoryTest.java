package com.javaclaw.platform.spring;

import com.javaclaw.browser.PlaywrightBrowserManager;
import com.javaclaw.platform.data.DataRoot;
import com.javaclaw.platform.execution.ManagedTaskExecutor;
import com.javaclaw.platform.execution.TaskScope;
import com.javaclaw.runtime.WorkspaceContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkspaceSpringContextFactoryTest {

    @TempDir
    Path tempDirectory;

    @Test
    void childUsesRootBeansAndDestroysWorkspaceResources() {
        Probe.CLOSED.set(false);
        try (var root = ApplicationContexts.createRoot(new DataRoot(tempDirectory.resolve("root")))) {
            WorkspaceSpringContextFactory factory = root.getBean(WorkspaceSpringContextFactory.class);
            WorkspaceContext workspace = workspace("workspace-a");

            WorkspaceContextHandle child = factory.create(
                    workspace, options(), TestWorkspaceConfiguration.class);

            assertSame(workspace, child.workspace());
            assertSame(root.getBean(JdbcTemplate.class), child.bean(JdbcTemplate.class));
            assertEquals("workspace-a", child.bean(Probe.class).workspaceId());
            assertFalse(Probe.CLOSED.get());
            child.close();
            assertTrue(Probe.CLOSED.get());
            assertTrue(child.isClosed());
        }
    }

    @Test
    void failedChildRefreshClosesBeansCreatedBeforeFailure() {
        Probe.CLOSED.set(false);
        try (var root = ApplicationContexts.createRoot(new DataRoot(tempDirectory.resolve("failure")))) {
            WorkspaceSpringContextFactory factory = root.getBean(WorkspaceSpringContextFactory.class);

            assertThrows(RuntimeException.class, () -> factory.create(
                    workspace("workspace-b"), options(), FailingWorkspaceConfiguration.class));
            assertTrue(Probe.CLOSED.get());
        }
    }

    @Test
    void productionWorkspaceRegistersOwnedWorkflowAndSddDeletionListeners() {
        try (var root = ApplicationContexts.createRoot(new DataRoot(tempDirectory.resolve("lifecycle")));
             var child = root.getBean(WorkspaceSpringContextFactory.class).create(workspace("lifecycle"), options())) {
            var threads = root.getBean(com.javaclaw.framework.api.ThreadClient.class);
            var parent = new com.javaclaw.framework.api.RunScope("lifecycle", "local-user", "conversation");
            var worker = new com.javaclaw.framework.api.RunScope("lifecycle", "local-user", "workflow-child");
            threads.start(com.javaclaw.framework.api.ThreadStartRequest.root(parent, "parent"));
            threads.start(new com.javaclaw.framework.api.ThreadStartRequest(worker, "worker", null, parent, null));
            var checkpoints = child.bean(com.javaclaw.workflow.store.GraphCheckpointStore.class);
            var run = new com.javaclaw.workflow.runtime.GraphRun(
                    com.javaclaw.workflow.editor.WorkflowEditorModel.blank("task"), worker.sessionId(),
                    new com.javaclaw.workflow.model.GraphState());
            checkpoints.createRun(run);
            checkpoints.checkpoint(run, "start", com.javaclaw.workflow.runtime.CheckpointPhase.BEFORE_NODE);
            threads.delete(parent);
            assertEquals(com.javaclaw.framework.api.ThreadStatus.DELETED,
                    root.getBean(com.javaclaw.framework.store.JdbcThreadStore.class).require(worker).status());
            assertEquals(null, checkpoints.loadRun(run.id()));
            assertThrows(IllegalStateException.class, () -> checkpoints.createRun(run));

            var tasks = child.bean(com.javaclaw.task.sdd.run.SddTaskManager.class);
            var task = tasks.create("task", "description", null, null, 1000, null, "2026-09-21");
            var coordinator = new com.javaclaw.framework.api.RunScope("lifecycle", "local-user",
                    "lifecycle:" + task.id + ":system-sdd");
            threads.start(com.javaclaw.framework.api.ThreadStartRequest.root(coordinator, "sdd"));
            threads.delete(coordinator);
            assertTrue(tasks.list().isEmpty());
            assertEquals(0, root.getBean(JdbcTemplate.class).queryForObject(
                    "SELECT COUNT(*) FROM sdd_tasks WHERE workspace_id='lifecycle'", Integer.class));
        }
    }

    private WorkspaceContext workspace(String id) {
        Path base = tempDirectory.resolve(id);
        return new WorkspaceContext(id, base, base.resolve("data"), base.resolve("browser"),
                base.resolve("shots"), base.resolve("logs"));
    }

    private WorkspaceRuntimeOptions options() {
        Path base = tempDirectory.resolve("browser-options");
        return new WorkspaceRuntimeOptions(
                new PlaywrightBrowserManager(true, base.resolve("browser"), base.resolve("shots")),
                () -> { }, () -> { }, () -> { }, Set.of());
    }

    @Configuration(proxyBeanMethods = false)
    static class TestWorkspaceConfiguration {
        @Bean(destroyMethod = "close")
        TaskScope taskScope(ManagedTaskExecutor executor, WorkspaceContext workspace) {
            return executor.openScope("test-" + workspace.workspaceId(), 2);
        }

        @Bean(destroyMethod = "close")
        Probe probe(WorkspaceContext workspace, JdbcTemplate jdbcTemplate) {
            return new Probe(workspace.workspaceId());
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class FailingWorkspaceConfiguration {
        @Bean(destroyMethod = "close")
        Probe probe(WorkspaceContext workspace) {
            return new Probe(workspace.workspaceId());
        }

        @Bean
        Object failure(Probe probe) {
            throw new IllegalStateException("simulated child creation failure");
        }
    }

    static final class Probe implements AutoCloseable {
        static final AtomicBoolean CLOSED = new AtomicBoolean(false);
        private final String workspaceId;

        Probe(String workspaceId) {
            this.workspaceId = workspaceId;
        }

        String workspaceId() {
            return workspaceId;
        }

        @Override
        public void close() {
            CLOSED.set(true);
        }
    }
}
