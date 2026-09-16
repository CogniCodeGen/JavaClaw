package com.javaclaw.server.persistence;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.javaclaw.api.AgentTurn;
import com.javaclaw.api.CorePayloads;
import com.javaclaw.api.CoreSchemas;
import com.javaclaw.api.MessageRole;
import com.javaclaw.api.ThreadExecutionIntent;
import com.javaclaw.api.ThreadId;
import com.javaclaw.api.TurnBudget;
import com.javaclaw.api.TurnId;
import com.javaclaw.api.TurnStatus;
import com.javaclaw.api.WorkspaceId;
import com.javaclaw.protocol.CanonicalJson;
import com.javaclaw.protocol.CoreItemCodecs;
import com.javaclaw.protocol.WriteCommand;
import com.javaclaw.server.TurnContractFixtures;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TurnContinuationTest {
    @TempDir
    Path directory;

    private final CanonicalJson json = new CanonicalJson();
    private final Clock clock = Clock.systemUTC();
    private final CorePayloads.Message message =
            new CorePayloads.Message(MessageRole.USER, "继续访问已授权网站", List.of(), Optional.empty());
    private H2Database database;
    private CoreCommandService core;
    private WorkspaceId workspace;
    private ThreadId thread;

    @BeforeEach
    void 初始化真实Core事务夹具() {
        database = new H2Database(directory.resolve("data-v6"));
        database.initialize();
        core = new CoreCommandService(database, json, clock);
        workspace = core.createWorkspace(
                        identity("workspace/create", "workspace"), "Continuation", directory.resolve("workspace"))
                .id();
        thread = createThread();
    }

    @Test
    void 续接保留唯一用户消息且重启后沿多段链接找到原始输入() {
        AgentTurn original = start(request(thread, message));
        finish(original.id());
        TurnStartRequest continued = request(thread, message).withContinuedFrom(original.id());
        CommandIdentity identity = identity("turn/start", continued);
        AgentTurn next = core.startTurn(identity, continued);
        assertEquals(next, core.startTurn(identity, continued));
        assertNotEquals(original.id(), next.id());
        assertEquals(original.id(), core.originalInputTurn(next.id()));
        assertEquals(message, core.turnUserMessage(next.id()));
        assertEquals(1, userItems());
        finish(next.id());
        AgentTurn last = start(request(thread, message).withContinuedFrom(next.id()));
        CoreCommandService reopened = new CoreCommandService(database, json, clock);
        assertEquals(original.id(), reopened.originalInputTurn(last.id()));
        assertEquals(message, reopened.turnUserMessage(last.id()));
        assertEquals(1, userItems());
        assertEquals(Optional.empty(), reopened.turnAssistantMessage(last.id()));
    }

    @Test
    void 续接拒绝活动前序跨对话和不同原始消息且失败不留下新Turn() throws Exception {
        AgentTurn original = start(request(thread, message));
        assertThrows(
                PersistenceException.class, () -> start(request(thread, message).withContinuedFrom(original.id())));
        finish(original.id());
        ThreadId other = createThread();
        assertThrows(
                PersistenceException.class, () -> start(request(other, message).withContinuedFrom(original.id())));
        CorePayloads.Message changed =
                new CorePayloads.Message(MessageRole.USER, "替换后的新任务", List.of(), Optional.empty());
        assertThrows(
                PersistenceException.class, () -> start(request(thread, changed).withContinuedFrom(original.id())));
        try (var connection = database.open();
                var statement = connection.createStatement();
                var rows = statement.executeQuery("SELECT COUNT(*) FROM CORE.AGENT_TURN")) {
            rows.next();
            assertEquals(1, rows.getInt(1));
        }
        assertEquals(1, userItems());
    }

    @Test
    void 同一前序只能有一个后继且新普通消息仍正常插入() {
        AgentTurn original = start(request(thread, message));
        finish(original.id());
        AgentTurn continued = start(request(thread, message).withContinuedFrom(original.id()));
        finish(continued.id());
        assertThrows(
                PersistenceException.class, () -> start(request(thread, message).withContinuedFrom(original.id())));
        AgentTurn ordinary = start(request(thread, message));
        assertEquals(ordinary.id(), core.originalInputTurn(ordinary.id()));
        assertEquals(2, userItems());
    }

    @Test
    void 受限Coding准备不会丢失续接来源() {
        AgentTurn original = start(request(thread, message));
        var selected = core.codingEnvironments().frozen(original.id()).inherited();
        var request = request(thread, message).withContinuedFrom(original.id()).withCodingEnvironment(selected);
        assertEquals(Optional.of(original.id()), request.continuedFrom());
        assertEquals(Optional.of(selected), request.codingEnvironment());
    }

    private ThreadId createThread() {
        return core.createThread(
                        identity("thread/create", "thread"),
                        workspace,
                        Optional.empty(),
                        ThreadExecutionIntent.WORKSPACE,
                        "Continuation")
                .id();
    }

    private TurnStartRequest request(ThreadId owner, CorePayloads.Message input) {
        return TurnContractFixtures.request(owner, new TurnBudget(1000, 1000, 2, 0, Duration.ofMinutes(1)), input);
    }

    private AgentTurn start(TurnStartRequest request) {
        return core.startTurn(identity("turn/start", request), request);
    }

    private void finish(TurnId turn) {
        new H2TurnJournal(database, CoreItemCodecs.createRegistry(json), json, clock)
                .transition(turn, TurnStatus.QUEUED, TurnStatus.CANCELLED, Optional.empty());
    }

    private CommandIdentity identity(String method, Object value) {
        return CommandIdentity.from(
                method, new WriteCommand(UUID.randomUUID().toString(), 0, json.encode(Map.of("value", value))), json);
    }

    private long userItems() {
        return core.listItems(thread).stream()
                .filter(item -> item.schemaId().equals(CoreSchemas.MESSAGE))
                .map(item -> json.decode(item.payload(), CorePayloads.Message.class))
                .filter(value -> value.role() == MessageRole.USER)
                .count();
    }
}
