package com.javaclaw.server.persistence;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.javaclaw.api.AgentTurn;
import com.javaclaw.api.ConversationThread;
import com.javaclaw.api.CorePayloads;
import com.javaclaw.api.MessageRole;
import com.javaclaw.api.ThreadExecutionIntent;
import com.javaclaw.api.ThreadId;
import com.javaclaw.api.TurnBudget;
import com.javaclaw.api.TurnCancelledException;
import com.javaclaw.api.TurnId;
import com.javaclaw.api.TurnStatus;
import com.javaclaw.api.Workspace;
import com.javaclaw.api.WorkspaceId;
import com.javaclaw.protocol.CanonicalJson;
import com.javaclaw.protocol.CoreRpcContracts;
import com.javaclaw.protocol.WriteCommand;
import com.javaclaw.server.TurnContractFixtures;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CoreAndTurnRepositoryBranchesTest {
    private static final Instant NOW = Instant.parse("2026-09-01T00:00:00Z");

    @TempDir
    Path temporaryDirectory;

    private H2Database database;
    private CanonicalJson json;
    private CoreCommandService core;
    private TurnRepository turns;
    private Workspace firstWorkspace;

    @BeforeEach
    void initializeDataV6() {
        database = new H2Database(temporaryDirectory.resolve("data-v6"));
        database.initialize();
        json = new CanonicalJson();
        core = new CoreCommandService(database, json, Clock.fixed(NOW, ZoneOffset.UTC));
        turns = new TurnRepository();
        firstWorkspace = createWorkspace("first");
    }

    @Test
    void turnRepository列出可恢复Turn并稳定查询活动关联() throws Exception {
        AgentTurn queued = createTurn(firstWorkspace, "queued");
        AgentTurn running = createTurn(firstWorkspace, "running");
        AgentTurn completed = createTurn(firstWorkspace, "completed");

        try (var connection = database.open()) {
            turns.transition(connection, running.id(), TurnStatus.QUEUED, TurnStatus.RUNNING, Optional.empty(), NOW);
            turns.transition(connection, completed.id(), TurnStatus.QUEUED, TurnStatus.RUNNING, Optional.empty(), NOW);
            turns.transition(
                    connection, completed.id(), TurnStatus.RUNNING, TurnStatus.COMPLETED, Optional.empty(), NOW);

            assertEquals(
                    java.util.stream.Stream.of(queued.id(), running.id())
                            .sorted(java.util.Comparator.comparing(TurnId::toString))
                            .toList(),
                    turns.listRecoverable(connection).stream()
                            .map(AgentTurn::id)
                            .toList());
            assertTrue(turns.hasActiveTurn(connection, queued.threadId()));
            assertFalse(turns.hasActiveTurn(connection, completed.threadId()));
            assertEquals(
                    queued.id(),
                    turns.findActiveByThread(connection, queued.threadId())
                            .orElseThrow()
                            .id());
            assertTrue(
                    turns.findActiveByThread(connection, completed.threadId()).isEmpty());
            assertEquals(queued.threadId(), turns.threadId(connection, queued.id()));
            turns.lockThread(connection, queued.threadId());
        }
    }

    @Test
    void turnRepository拒绝缺失关联陈旧状态和终态取消() throws Exception {
        AgentTurn queued = createTurn(firstWorkspace, "invalid");
        AgentTurn terminal = createTurn(firstWorkspace, "terminal");
        try (var connection = database.open()) {
            assertThrows(PersistenceException.class, () -> turns.lockThread(connection, ThreadId.random()));
            assertThrows(PersistenceException.class, () -> turns.threadId(connection, TurnId.random()));
            assertThrows(
                    PersistenceException.class,
                    () -> turns.transition(
                            connection, queued.id(), TurnStatus.RUNNING, TurnStatus.COMPLETED, Optional.empty(), NOW));
            assertThrows(
                    PersistenceException.class,
                    () -> turns.requestCancellation(connection, queued.id(), 2, "用户取消", NOW));

            AgentTurn cancellation = turns.requestCancellation(connection, queued.id(), 1, "用户取消", NOW);
            assertEquals(2, cancellation.revision());
            assertThrows(
                    TurnCancelledException.class,
                    () -> turns.transition(
                            connection, queued.id(), TurnStatus.QUEUED, TurnStatus.COMPLETED, Optional.empty(), NOW));

            turns.transition(connection, terminal.id(), TurnStatus.QUEUED, TurnStatus.CANCELLED, Optional.empty(), NOW);
            assertThrows(
                    PersistenceException.class,
                    () -> turns.requestCancellation(connection, terminal.id(), 2, "再次取消", NOW));
            assertThrows(
                    PersistenceException.class,
                    () -> turns.requestCancellation(connection, TurnId.random(), 1, "缺失", NOW));
        }
    }

    @Test
    void core拒绝跨Workspace父子关系和缺失父Thread() {
        Workspace second = createWorkspace("second");
        ConversationThread parent = createThread(firstWorkspace, Optional.empty(), "parent");

        assertThrows(
                PersistenceException.class,
                () -> core.createThread(
                        identity("thread/create", "cross-parent", 0, "cross"),
                        second.id(),
                        Optional.of(parent.id()),
                        ThreadExecutionIntent.WORKSPACE,
                        "cross"));
        assertThrows(
                PersistenceException.class,
                () -> core.createThread(
                        identity("thread/create", "missing-parent", 0, "missing"),
                        firstWorkspace.id(),
                        Optional.of(ThreadId.random()),
                        ThreadExecutionIntent.WORKSPACE,
                        "missing"));
        assertThrows(
                PersistenceException.class,
                () -> core.createThread(
                        identity("thread/create", "missing-workspace", 0, "missing"),
                        WorkspaceId.random(),
                        Optional.empty(),
                        ThreadExecutionIntent.WORKSPACE,
                        "missing"));
    }

    @Test
    void core项目约定更新与Workspace命令校验保留权威revision() {
        var initial = core.workspaceInstructionSettings(firstWorkspace.id());
        CommandIdentity update = identity("workspace/instructions/update", "instructions", 1, "AGENT.local.md");
        var updated =
                core.updateWorkspaceInstructionSettings(update, firstWorkspace.id(), Optional.of("AGENT.local.md"));

        assertEquals(2, updated.revision());
        assertEquals(
                updated,
                core.updateWorkspaceInstructionSettings(update, firstWorkspace.id(), Optional.of("AGENT.local.md")));
        assertEquals(1, initial.revision());
        assertThrows(
                PersistenceException.class,
                () -> core.updateWorkspaceInstructionSettings(
                        identity("workspace/instructions/update", "zero", 0, "x.md"),
                        firstWorkspace.id(),
                        Optional.of("x.md")));
        assertThrows(
                PersistenceException.class,
                () -> core.updateWorkspaceInstructionSettings(
                        identity("workspace/instructions/update", "stale", 1, "x.md"),
                        firstWorkspace.id(),
                        Optional.of("x.md")));
        assertThrows(PersistenceException.class, () -> core.workspaceInstructionSettings(WorkspaceId.random()));
        assertThrows(
                PersistenceException.class,
                () -> core.createWorkspace(
                        identity("workspace/create", "bad-create", 1, "bad"),
                        "Bad",
                        temporaryDirectory.resolve("bad")));
    }

    @Test
    void core取消和Item分页拒绝非法revision与游标() {
        AgentTurn turn = createTurn(firstWorkspace, "core-cancel");

        assertThrows(
                PersistenceException.class,
                () -> core.requestTurnCancellation(
                        identity("turn/cancel", "cancel-zero", 0, "cancel"), turn.id(), "用户取消"));
        AgentTurn cancelled =
                core.requestTurnCancellation(identity("turn/cancel", "cancel", 1, "cancel"), turn.id(), "用户取消");
        assertEquals(2, cancelled.revision());
        assertThrows(IllegalArgumentException.class, () -> core.listItems(turn.threadId(), -1, 10));
        assertThrows(IllegalArgumentException.class, () -> core.listItems(turn.threadId(), 0, 0));
        assertThrows(IllegalArgumentException.class, () -> core.listItems(turn.threadId(), 0, 1_001));
        assertFalse(core.listItems(turn.threadId()).isEmpty());
    }

    @Test
    void core缺失Thread与Turn查询在边界处FailClosed() {
        assertThrows(PersistenceException.class, () -> core.workspaceForThread(ThreadId.random()));
        assertThrows(PersistenceException.class, () -> core.turnUserMessage(TurnId.random()));
        assertThrows(PersistenceException.class, () -> core.promptSnapshot(TurnId.random()));
        assertThrows(PersistenceException.class, () -> core.toolCatalogSnapshot(TurnId.random()));
        assertTrue(core.findThread(ThreadId.random()).isEmpty());
        assertTrue(core.findTurn(TurnId.random()).isEmpty());
    }

    private Workspace createWorkspace(String suffix) {
        CoreRpcContracts.WorkspaceCreatePayload payload = new CoreRpcContracts.WorkspaceCreatePayload(
                "Workspace " + suffix, temporaryDirectory.resolve("workspace-" + suffix));
        return core.createWorkspace(
                identity("workspace/create", "workspace-" + suffix, 0, payload), payload.name(), payload.root());
    }

    private ConversationThread createThread(Workspace workspace, Optional<ThreadId> parentId, String suffix) {
        CoreRpcContracts.ThreadCreatePayload payload = new CoreRpcContracts.ThreadCreatePayload(
                workspace.id(), parentId, ThreadExecutionIntent.WORKSPACE, suffix);
        return core.createThread(
                identity("thread/create", "thread-" + suffix, 0, payload),
                workspace.id(),
                parentId,
                ThreadExecutionIntent.WORKSPACE,
                suffix);
    }

    private AgentTurn createTurn(Workspace workspace, String suffix) {
        ConversationThread thread = createThread(workspace, Optional.empty(), suffix);
        CorePayloads.Message message = new CorePayloads.Message(MessageRole.USER, suffix, List.of(), Optional.empty());
        CoreRpcContracts.TurnStartPayload payload = TurnContractFixtures.payload(thread.id(), suffix);
        return core.startTurn(
                identity("turn/start", "turn-" + suffix, 0, payload),
                TurnContractFixtures.request(thread.id(), budget(), message));
    }

    private CommandIdentity identity(String method, String key, long revision, Object payload) {
        return CommandIdentity.from(
                method, new WriteCommand(key, revision, json.encode(java.util.Map.of("payload", payload))), json);
    }

    private static TurnBudget budget() {
        return new TurnBudget(4_000, 1_000, 2, 0, Duration.ofMinutes(1));
    }
}
