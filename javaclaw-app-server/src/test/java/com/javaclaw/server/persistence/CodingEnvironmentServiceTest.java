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
import com.javaclaw.builtin.contracts.CodingEnvironmentContracts.Environment;
import com.javaclaw.builtin.contracts.CodingEnvironmentContracts.ToolchainKind;
import com.javaclaw.protocol.CanonicalJson;
import com.javaclaw.protocol.WriteCommand;
import com.javaclaw.server.TurnContractFixtures;
import com.javaclaw.server.toolchain.CodingEnvironmentSelection;
import com.javaclaw.server.toolchain.CodingToolchainCatalog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CodingEnvironmentServiceTest {
    @TempDir
    Path temporary;

    private final CanonicalJson json = new CanonicalJson();
    private final Clock clock = Clock.systemUTC();
    private H2Database database;
    private CoreCommandService core;
    private Workspace workspace;
    private CodingEnvironmentSelection selection;

    @BeforeEach
    void 初始化真实H2与显式项目选择() {
        database = new H2Database(temporary.resolve("data-v6"));
        database.initialize();
        core = new CoreCommandService(database, json, clock);
        workspace =
                core.createWorkspace(identity("workspace/create", "workspace", 0), "项目", temporary.resolve("project"));
        selection = new CodingEnvironmentSelection(
                new Environment(0, CodingToolchainCatalog.bundled().defaultEnvironment()),
                Map.of("pom.xml", "a".repeat(64)),
                Map.of(),
                true);
    }

    @Test
    void 普通Turn同事务固定项目选择且幂等恢复不重新读取或校验新配置() {
        var request = request("one").withCodingEnvironment(selection);
        var identity = identity("turn/start", "one", 0);
        var turn = core.startTurn(identity, request);
        updateEnvironment();
        assertEquals(selection.inherited(), core.codingEnvironments().frozen(turn.id()));
        assertEquals(turn, core.startTurn(identity, request));
    }

    @Test
    void 事务外准备后配置改变不能拼接不同版本且失败不创建Turn() throws Exception {
        var request = request("race").withCodingEnvironment(selection);
        updateEnvironment();
        assertThrows(PersistenceException.class, () -> core.startTurn(identity("turn/start", "race", 0), request));
        int count = new H2Transactions(database).execute(connection -> {
            try (var statement = connection.createStatement();
                    var rows = statement.executeQuery("SELECT COUNT(*) FROM CORE.AGENT_TURN")) {
                rows.next();
                return rows.getInt(1);
            }
        });
        assertEquals(0, count);
    }

    @Test
    void 子Execution继承父版本且旧Execution缺少证据不能获得Coding执行() {
        var turn = core.startTurn(
                identity("turn/start", "parent", 0), request("parent").withCodingEnvironment(selection));
        updateEnvironment();
        TurnId snapshot = TurnId.random();
        core.codingEnvironments().freezeChild(snapshot, workspace.id(), turn.id());
        assertEquals(selection.inherited(), core.codingEnvironments().execution(snapshot, workspace.id()));
        var legacy = core.codingEnvironments().execution(TurnId.random(), workspace.id());
        assertFalse(legacy.inspected());
        assertEquals(List.of(), legacy.environment().spec().toolchains());
        assertThrows(IllegalStateException.class, () -> legacy.requireCompatible(List.of(ToolchainKind.JDK)));
    }

    @Test
    void 旧Turn缺少声明列时保留原环境但禁止新增命令能力() throws Exception {
        var turn = core.startTurn(
                identity("turn/start", "legacy", 0), request("legacy").withCodingEnvironment(selection));
        new H2Transactions(database).execute(connection -> {
            try (var statement = connection.prepareStatement("""
                    UPDATE CORE.TURN_CODING_ENVIRONMENT SET SELECTION_JSON=NULL,SELECTION_DIGEST=NULL WHERE TURN_ID=?
                    """)) {
                statement.setString(1, turn.id().toString());
                statement.executeUpdate();
            }
            return null;
        });
        var restored = core.codingEnvironments().frozen(turn.id());
        assertEquals(selection.environment(), restored.environment());
        assertFalse(restored.inspected());
        assertThrows(IllegalStateException.class, () -> restored.requireCompatible(List.of(ToolchainKind.NODE)));
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

    private void updateEnvironment() {
        new CodingEnvironmentRepository(database, json, clock)
                .update(
                        workspace.id(),
                        identity("coding/environment-update", "update", 0),
                        selection.environment().spec());
    }

    private CommandIdentity identity(String method, String key, long revision) {
        return CommandIdentity.from(method, new WriteCommand(key, revision, json.encode(Map.of("key", key))), json);
    }
}
