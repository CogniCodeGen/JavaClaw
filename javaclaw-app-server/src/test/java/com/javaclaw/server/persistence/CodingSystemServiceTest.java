package com.javaclaw.server.persistence;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.javaclaw.api.CorePayloads;
import com.javaclaw.api.MessageRole;
import com.javaclaw.api.ThreadExecutionIntent;
import com.javaclaw.api.TurnBudget;
import com.javaclaw.api.TurnId;
import com.javaclaw.api.Workspace;
import com.javaclaw.builtin.contracts.CodingSystemContracts.Registration;
import com.javaclaw.builtin.contracts.CodingSystemContracts.RegistryUpdate;
import com.javaclaw.protocol.CanonicalJson;
import com.javaclaw.protocol.WriteCommand;
import com.javaclaw.server.TurnContractFixtures;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CodingSystemServiceTest {
    @TempDir
    Path temporary;

    private final CanonicalJson json = new CanonicalJson();
    private final Clock clock = Clock.systemUTC();
    private H2Database database;
    private CoreCommandService core;
    private Workspace workspace;
    private CodingSystemService service;

    @BeforeEach
    void 创建真实数据库及Workspace() {
        database = new H2Database(temporary.resolve("data-v6"));
        database.initialize();
        core = new CoreCommandService(database, json, clock);
        workspace = core.createWorkspace(
                identity("workspace/create", "workspace", 0), "系统程序", temporary.resolve("project"));
        service = core.systemCommands();
    }

    @Test
    void 注册配置使用版本和幂等身份且缺失入口不阻断Turn() {
        var update = new RegistryUpdate(List.of(missing("custom")));
        var command = identity("system/registry/update", "register", 0);
        var registered = service.update(workspace.id(), command, update);
        assertEquals(1, registered.revision());
        assertEquals(registered, service.update(workspace.id(), command, update));
        assertThrows(
                PersistenceException.class,
                () -> service.update(workspace.id(), identity("system/registry/update", "stale", 0), update));
        var turn = core.startTurn(identity("turn/start", "one", 0), request("one"));
        var catalog = service.frozen(turn.id());
        var entry = catalog.executables().stream()
                .filter(value -> value.id().equals("custom"))
                .findFirst()
                .orElseThrow();
        assertFalse(entry.available());
        assertEquals("SYSTEM_EXECUTABLE_MISSING", entry.unavailableReason().orElseThrow());
        assertEquals(1, catalog.registryRevision());
    }

    @Test
    void 配置改变不能替换已有Turn且事务外发现后版本冲突拒绝提交() {
        service.update(
                workspace.id(),
                identity("system/registry/update", "first", 0),
                new RegistryUpdate(List.of(missing("first"))));
        var turn = core.startTurn(identity("turn/start", "one", 0), request("one"));
        var before = service.frozen(turn.id());
        var pending = service.prepare(request("two"));
        service.update(
                workspace.id(),
                identity("system/registry/update", "second", 1),
                new RegistryUpdate(List.of(missing("second"))));
        assertEquals(before, service.frozen(turn.id()));
        assertThrows(PersistenceException.class, () -> core.startTurn(identity("turn/start", "two", 0), pending));
    }

    @Test
    void 自动化及子执行继承原目录且旧快照不会补当前注册() {
        var turn = core.startTurn(identity("turn/start", "one", 0), request("one"));
        TurnId child = TurnId.random();
        service.freezeChild(child, workspace.id(), turn.id());
        assertEquals(
                service.frozen(turn.id()),
                service.execution(child, workspace.id()).catalog());
        TurnId execution = TurnId.random();
        service.freezeExecution(execution, workspace.id());
        var original = service.execution(execution, workspace.id());
        service.update(
                workspace.id(),
                identity("system/registry/update", "new", 0),
                new RegistryUpdate(List.of(missing("late"))));
        service.freezeExecution(execution, workspace.id());
        assertEquals(original, service.execution(execution, workspace.id()));
        assertEquals(
                "unavailable",
                service.execution(TurnId.random(), workspace.id()).catalog().platform());
        assertEquals("unavailable", service.frozen(TurnId.random()).platform());
    }

    @Test
    void 持久快照摘要损坏不得作为当前目录恢复() throws Exception {
        var turn = core.startTurn(identity("turn/start", "one", 0), request("one"));
        try (var connection = database.open();
                var statement = connection.createStatement()) {
            statement.execute("UPDATE CORE.TURN_SYSTEM_ENVIRONMENT SET CATALOG_DIGEST=REPEAT('f',64)");
        }
        assertThrows(SecurityException.class, () -> service.frozen(turn.id()));
        assertTrue(service.catalog(workspace.id()).executables().stream()
                .allMatch(value -> value.id().startsWith("system.")));
    }

    private Registration missing(String id) {
        return new Registration(id, temporary.resolve("missing-" + id).toString(), List.of(), "UTF-8");
    }

    private TurnStartRequest request(String name) {
        var thread = core.createThread(
                identity("thread/create", "thread-" + name, 0),
                workspace.id(),
                Optional.empty(),
                ThreadExecutionIntent.WORKSPACE,
                name);
        return TurnContractFixtures.request(
                thread.id(),
                new TurnBudget(100, 100, 2, 0, Duration.ofSeconds(5)),
                new CorePayloads.Message(MessageRole.USER, "继续", List.of(), Optional.empty()));
    }

    private CommandIdentity identity(String method, String key, long revision) {
        return CommandIdentity.from(method, new WriteCommand(key, revision, json.encode(Map.of("key", key))), json);
    }
}
