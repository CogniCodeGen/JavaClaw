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
