package com.javaclaw.server.persistence;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import com.javaclaw.api.ExecutionOverrides;
import com.javaclaw.api.Workspace;
import com.javaclaw.api.WorkspaceLifecycle;
import com.javaclaw.protocol.CanonicalJson;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkspaceCreationConflictTest {
    private static final Instant NOW = Instant.parse("2026-09-09T10:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final Optional<ExecutionOverrides> EXECUTION = Optional.of(ExecutionOverrides.empty());
    private final CanonicalJson json = new CanonicalJson();

    @TempDir
    Path temporaryDirectory;

    private H2Database database;
    private CoreCommandService service;

    @BeforeEach
    void initialize() {
        database = new H2Database(temporaryDirectory.resolve("data-v6"));
        database.initialize();
        service = new CoreCommandService(database, json, CLOCK);
    }

    @Test
    void 同一规范目录新命令返回已有名称而幂等回放仍返回原回执() throws Exception {
        Path root = temporaryDirectory.resolve("project");
        Workspace created = create("first", "自定义中文名称", root);
        PersistenceException failure =
                assertThrows(PersistenceException.class, () -> create("second", "另一名称", root.resolve("child/..")));
        assertEquals(PersistenceException.Kind.INVALID_REQUEST, failure.kind());
        assertTrue(failure.getMessage().contains("自定义中文名称"));
        assertTrue(failure.getMessage().contains("打开已有工作区"));
        assertTrue(failure.getMessage().contains("修改名称"));
        assertEquals(created, create("first", "自定义中文名称", root));
        assertEquals(List.of(created), service.listWorkspaces());
        assertCreationRows(1, 1);
        Workspace other = create("third", "另一个中文项目", temporaryDirectory.resolve("other"));
        assertEquals("另一个中文项目", other.name());
        assertCreationRows(2, 2);
    }

    @Test
    void 已归档目录明确拒绝重新创建且不恢复生命周期() throws Exception {
        Path root = temporaryDirectory.resolve("archived");
        Workspace created = create("first", "归档项目", root);
        Workspace archived = service.archiveWorkspace(identity("workspace/archive", "archive", 1), created.id());
        PersistenceException failure = assertThrows(PersistenceException.class, () -> create("again", "重开", root));
        assertEquals(PersistenceException.Kind.INVALID_REQUEST, failure.kind());
        assertTrue(failure.getMessage().contains("归档项目"));
        assertTrue(failure.getMessage().contains("不会自动重新启用"));
        assertEquals(
                WorkspaceLifecycle.ARCHIVED,
                service.findWorkspace(created.id()).orElseThrow().lifecycle());
        assertEquals(archived, service.findWorkspace(created.id()).orElseThrow());
        assertCreationRows(1, 2);
    }

    @Test
    void 名称与路径长度在写入前校验且重命名失败保留原版本() throws Exception {
        Path root = temporaryDirectory.resolve("length");
        PersistenceException nameFailure =
                assertThrows(PersistenceException.class, () -> create("too-long", "名".repeat(241), root));
        assertEquals(PersistenceException.Kind.INVALID_REQUEST, nameFailure.kind());
        assertTrue(nameFailure.getMessage().contains("240"));
        PersistenceException pathFailure = assertThrows(
                PersistenceException.class, () -> create("path-long", "路径项目", Path.of("/" + "p".repeat(4096))));
        assertEquals(PersistenceException.Kind.INVALID_REQUEST, pathFailure.kind());
        assertTrue(pathFailure.getMessage().contains("4096"));
        assertCreationRows(0, 0);
        Workspace created = create("valid", "名".repeat(240), root);
        PersistenceException renameFailure = assertThrows(
                PersistenceException.class,
                () -> service.renameWorkspace(
                        identity("workspace/rename", "rename", 1), created.id(), "名".repeat(241)));
        assertEquals(PersistenceException.Kind.INVALID_REQUEST, renameFailure.kind());
        assertEquals(created, service.findWorkspace(created.id()).orElseThrow());
        assertCreationRows(1, 1);
    }

    @Test
    @Timeout(20)
    void 并发创建相同根只有一个完整提交而另一请求返回可操作冲突() throws Exception {
        Path root = temporaryDirectory.resolve("concurrent");
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(() -> attempt("first", "并发甲", root, start));
            var second = executor.submit(() -> attempt("second", "并发乙", root, start));
            start.countDown();
            List<Object> outcomes = List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));
            assertEquals(
                    1, outcomes.stream().filter(Workspace.class::isInstance).count());
            PersistenceException failure = outcomes.stream()
                    .filter(PersistenceException.class::isInstance)
                    .map(PersistenceException.class::cast)
                    .findFirst()
                    .orElseThrow();
            assertEquals(PersistenceException.Kind.INVALID_REQUEST, failure.kind());
            assertTrue(failure.getMessage().contains("打开已有工作区"));
            assertCreationRows(1, 1);
        }
    }

    @Test
    @Timeout(20)
    void 旧串行化快照中的唯一冲突在原事务回滚后核验赢家() throws Exception {
        Path root = temporaryDirectory.resolve("snapshot");
        WorkspaceCreation creation = new WorkspaceCreation(database, json);
        CountDownLatch snapshot = new CountDownLatch(1);
        CountDownLatch committed = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var loser = executor.submit(() -> assertThrows(
                    PersistenceException.class,
                    () -> creation.execute(
                            root,
                            () -> transaction(connection -> {
                                assertTrue(new WorkspaceRepository()
                                        .findByRoot(connection, root)
                                        .isEmpty());
                                snapshot.countDown();
                                assertTrue(committed.await(10, TimeUnit.SECONDS));
                                return creation.insert(connection, "竞争者", root, EXECUTION, NOW);
                            }))));
            try {
                assertTrue(snapshot.await(10, TimeUnit.SECONDS));
                create("winner", "已提交赢家", root);
            } finally {
                committed.countDown();
            }
            PersistenceException failure = loser.get(10, TimeUnit.SECONDS);
            assertEquals(PersistenceException.Kind.INVALID_REQUEST, failure.kind());
            assertTrue(failure.getMessage().contains("已提交赢家"));
            assertCreationRows(1, 1);
        }
    }

    @Test
    void 后续其他唯一冲突和普通SQL错误仍为内部失败且创建副写入全部回滚() throws Exception {
        try (Connection connection = database.open();
                var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE CORE.WORKSPACE_CREATION_TEST (ID INTEGER PRIMARY KEY)");
            statement.execute("INSERT INTO CORE.WORKSPACE_CREATION_TEST VALUES (1)");
        }
        WorkspaceCreation creation = new WorkspaceCreation(database, json);
        for (String sql : List.of(
                "INSERT INTO CORE.WORKSPACE_CREATION_TEST VALUES (1)",
                "SELECT MISSING_COLUMN FROM CORE.WORKSPACE_CREATION_TEST")) {
            Path root = temporaryDirectory.resolve("failed-write");
            PersistenceException failure = assertThrows(
                    PersistenceException.class,
                    () -> creation.execute(
                            root,
                            () -> transaction(connection -> {
                                Workspace workspace = creation.insert(connection, "不会残留", root, EXECUTION, NOW);
                                try (var statement = connection.createStatement()) {
                                    statement.execute(sql);
                                }
                                return workspace;
                            })));
            assertEquals(PersistenceException.Kind.INTERNAL, failure.kind());
            assertInstanceOf(SQLException.class, failure.getCause());
            assertCreationRows(0, 0);
        }
        PersistenceException original = new PersistenceException("unrelated");
        assertSame(
                original,
                assertThrows(
                        PersistenceException.class,
                        () -> creation.execute(temporaryDirectory, () -> {
                            throw original;
                        })));
    }

    @Test
    void 副写入唯一冲突回滚后另一创建占用同root仍保留原内部错误() throws Exception {
        Path root = temporaryDirectory.resolve("later-owner");
        WorkspaceCreation creation = new WorkspaceCreation(database, json);
        AtomicReference<PersistenceException> original = new AtomicReference<>();
        PersistenceException failure = assertThrows(
                PersistenceException.class,
                () -> creation.execute(root, () -> {
                    try {
                        return transaction(connection -> {
                            Workspace workspace = creation.insert(connection, "回滚项目", root, EXECUTION, NOW);
                            new WorkspaceInstructionSettingsRepository().insert(connection, workspace.id(), NOW);
                            return workspace;
                        });
                    } catch (PersistenceException writeFailure) {
                        original.set(writeFailure);
                        // 固定竞争窗口：原事务已回滚，另一请求先提交同 root，随后才进入外层错误分类。
                        create("later-winner", "随后登记成功", root);
                        throw writeFailure;
                    }
                }));
        assertSame(original.get(), failure);
        assertEquals(PersistenceException.Kind.INTERNAL, failure.kind());
        assertEquals(
                "23505",
                assertInstanceOf(SQLException.class, failure.getCause()).getSQLState());
        assertEquals("随后登记成功", service.listWorkspaces().getFirst().name());
        assertCreationRows(1, 1);
    }

    private Object attempt(String key, String name, Path root, CountDownLatch start) throws InterruptedException {
        start.await();
        try {
            return create(key, name, root);
        } catch (PersistenceException failure) {
            return failure;
        }
    }

    private Workspace create(String key, String name, Path root) {
        return service.createWorkspace(identity("workspace/create", key, 0), name, root, EXECUTION);
    }

    private Workspace transaction(H2Transactions.SqlWork<Workspace> work) {
        try {
            return new H2Transactions(database).execute(work);
        } catch (RuntimeException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new PersistenceException("测试事务失败", failure);
        }
    }

    private void assertCreationRows(int workspaces, int commands) throws SQLException {
        try (Connection connection = database.open()) {
            for (String table : List.of("WORKSPACE", "WORKSPACE_INSTRUCTION_SETTING", "EXECUTION_CONFIGURATION")) {
                assertEquals(workspaces, count(connection, table), table);
            }
            assertEquals(commands, count(connection, "COMMAND_RESULT"));
        }
    }

    private int count(Connection connection, String table) throws SQLException {
        try (var statement = connection.createStatement();
                var result = statement.executeQuery("SELECT COUNT(*) FROM CORE." + table)) {
            result.next();
            return result.getInt(1);
        }
    }

    private static CommandIdentity identity(String method, String key, long revision) {
        return new CommandIdentity(method, key, revision, "a".repeat(64));
    }
}
