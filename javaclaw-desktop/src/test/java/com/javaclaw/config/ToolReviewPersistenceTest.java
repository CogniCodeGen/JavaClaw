package com.javaclaw.config;

import com.javaclaw.infrastructure.config.AgentConfigToolReviewSettings;
import com.javaclaw.platform.data.DataRoot;
import com.javaclaw.platform.data.H2DataSource;
import com.javaclaw.platform.data.SchemaInitializer;
import com.javaclaw.platform.execution.ManagedTaskExecutor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class ToolReviewPersistenceTest {
    @TempDir Path temporary;

    @Test
    void failureIsObservableWithoutRollingBackTheEffectiveModeAndRetryPersistsIt() throws Exception {
        try (var fixture = new Fixture()) {
            fixture.settings.update(ToolReviewMode.MANUAL).toCompletableFuture().get(5, TimeUnit.SECONDS);
            fixture.jdbc.fail = true;
            var saved = fixture.settings.update(ToolReviewMode.AUTO).toCompletableFuture();
            assertThrows(ExecutionException.class, () -> saved.get(5, TimeUnit.SECONDS));
            assertEquals(ToolReviewMode.AUTO, fixture.settings.current());
            assertEquals(ToolReviewMode.MANUAL.id(), fixture.persisted());
            fixture.jdbc.fail = false;
            fixture.settings.update(ToolReviewMode.AUTO).toCompletableFuture().get(5, TimeUnit.SECONDS);
            assertEquals(ToolReviewMode.AUTO.id(), fixture.persisted());
        }
    }

    @Test
    void queuedWritesRetainTheirWorkspaceAndSubmissionOrder() throws Exception {
        try (var fixture = new Fixture()) {
            fixture.jdbc.release = new CountDownLatch(1);
            var first = fixture.settings.update(ToolReviewMode.MANUAL);
            assertTrue(fixture.jdbc.entered.await(5, TimeUnit.SECONDS));
            fixture.workspace.set("second");
            fixture.config.reload();
            var second = fixture.settings.update(ToolReviewMode.AUTO);
            var latest = fixture.settings.update(ToolReviewMode.SMART);
            fixture.jdbc.release.countDown();
            first.toCompletableFuture().get(5, TimeUnit.SECONDS);
            second.toCompletableFuture().get(5, TimeUnit.SECONDS);
            latest.toCompletableFuture().get(5, TimeUnit.SECONDS);
            assertEquals(ToolReviewMode.SMART.id(), fixture.persisted());
            fixture.workspace.set("first");
            assertEquals(ToolReviewMode.MANUAL.id(), fixture.persisted());
        }
    }

    @Test
    void cancellingAnObserversFutureDoesNotCancelTheAcceptedWrite() throws Exception {
        try (var fixture = new Fixture()) {
            fixture.jdbc.release = new CountDownLatch(1);
            var saved = fixture.settings.update(ToolReviewMode.AUTO);
            assertTrue(fixture.jdbc.entered.await(5, TimeUnit.SECONDS));
            saved.toCompletableFuture().cancel(true);
            fixture.jdbc.release.countDown();
            saved.toCompletableFuture().get(5, TimeUnit.SECONDS);
            assertEquals(ToolReviewMode.AUTO.id(), fixture.persisted());
        }
    }

    @Test
    void closedSettingsRejectBeforeChangingTheEffectiveMode() throws Exception {
        try (var fixture = new Fixture()) {
            fixture.settings.close();
            assertThrows(java.util.concurrent.RejectedExecutionException.class,
                    () -> fixture.settings.update(ToolReviewMode.AUTO));
            assertEquals(ToolReviewMode.SMART, fixture.settings.current());
        }
    }

    private final class Fixture implements AutoCloseable {
        private final AtomicReference<String> workspace = new AtomicReference<>("first");
        private final H2DataSource dataSource;
        private final RecordingJdbc jdbc;
        private final SqlPropertyStore store;
        private final AgentConfig config;
        private final ManagedTaskExecutor tasks = new ManagedTaskExecutor();
        private final AgentConfigToolReviewSettings settings;

        Fixture() throws Exception {
            var root = new DataRoot(temporary);
            root.prepare();
            dataSource = new H2DataSource(root);
            new SchemaInitializer(dataSource).initialize();
            jdbc = new RecordingJdbc(dataSource);
            store = new SqlPropertyStore(jdbc, new DataSourceTransactionManager(dataSource), workspace::get);
            DatabaseAccess database = new DatabaseAccess() {
                @Override public Connection open() throws SQLException { return dataSource.getConnection(); }
                @Override public String description() { return "隔离审核设置测试"; }
            };
            var cipher = (CredentialCipher) java.lang.reflect.Proxy.newProxyInstance(
                    CredentialCipher.class.getClassLoader(), new Class<?>[]{CredentialCipher.class},
                    (proxy, method, args) -> { throw new AssertionError("审核模式不应访问凭据"); });
            config = new AgentConfig(store, database, cipher);
            settings = new AgentConfigToolReviewSettings(tasks, config);
        }

        String persisted() {
            return store.load(AgentConfigSchema.CONFIG_NAMESPACE).getProperty(AgentConfigSchema.KEY_TOOL_REVIEW_MODE);
        }

        @Override public void close() throws Exception {
            if (jdbc.release != null) jdbc.release.countDown();
            settings.close();
            tasks.close();
            dataSource.close();
        }
    }

    private static final class RecordingJdbc extends JdbcTemplate {
        private volatile boolean fail;
        private volatile CountDownLatch release;
        private final CountDownLatch entered = new CountDownLatch(1);

        RecordingJdbc(H2DataSource source) { super(source); }

        @Override public int update(String sql, Object... arguments) {
            if (sql.contains("MERGE INTO app_properties")) {
                entered.countDown();
                var gate = release;
                if (gate != null) {
                    try {
                        if (!gate.await(5, TimeUnit.SECONDS)) throw new AssertionError("测试保存等待超时");
                    } catch (InterruptedException cancelled) {
                        Thread.currentThread().interrupt();
                        throw new DataAccessResourceFailureException("测试保存已取消", cancelled);
                    }
                }
                if (fail) throw new DataAccessResourceFailureException("模拟数据库保存失败");
            }
            return super.update(sql, arguments);
        }
    }
}
