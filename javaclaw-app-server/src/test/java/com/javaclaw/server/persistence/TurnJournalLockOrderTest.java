package com.javaclaw.server.persistence;

import java.nio.file.Path;
import java.sql.Connection;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.javaclaw.api.AgentTurn;
import com.javaclaw.api.ApprovalDecision;
import com.javaclaw.api.ApprovalRequest;
import com.javaclaw.api.ApprovalState;
import com.javaclaw.api.CancellationSource;
import com.javaclaw.api.CorePayloads;
import com.javaclaw.api.MessageRole;
import com.javaclaw.api.ThreadExecutionIntent;
import com.javaclaw.api.ToolCallRequest;
import com.javaclaw.api.ToolCatalogSnapshot;
import com.javaclaw.api.ToolIdentity;
import com.javaclaw.api.ToolRisk;
import com.javaclaw.api.TurnBudget;
import com.javaclaw.api.TurnId;
import com.javaclaw.api.TurnStatus;
import com.javaclaw.protocol.CanonicalJson;
import com.javaclaw.protocol.CoreItemCodecs;
import com.javaclaw.protocol.CoreRpcContracts;
import com.javaclaw.protocol.TurnStreamRpcContracts;
import com.javaclaw.protocol.WriteCommand;
import com.javaclaw.runtime.CompactionOutcome;
import com.javaclaw.runtime.CompactionRequest;
import com.javaclaw.runtime.ConversationWindow;
import com.javaclaw.runtime.ModelFinishReason;
import com.javaclaw.runtime.ModelInvocationResult;
import com.javaclaw.runtime.ModelStreamEvent;
import com.javaclaw.runtime.ModelToolCall;
import com.javaclaw.runtime.ModelUsage;
import com.javaclaw.runtime.TurnExecutionCommand;
import com.javaclaw.server.TurnContractFixtures;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 用真实 SQL 阻塞关系建立并发屏障，不依赖 sleep 猜测事务已走到哪一步。 */
class TurnJournalLockOrderTest {
    private static final Instant NOW = Instant.parse("2026-09-01T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final ModelUsage USAGE = new ModelUsage(1, 1, 0, 0);

    @TempDir
    Path temporaryDirectory;

    private final CanonicalJson json = new CanonicalJson();
    private H2Database database;
    private CoreCommandService core;
    private H2TurnJournal journal;

    @BeforeEach
    void initialize() {
        database = new H2Database(temporaryDirectory.resolve("data-v6"));
        database.initialize();
        core = new CoreCommandService(database, json, CLOCK);
        journal = new H2TurnJournal(database, CoreItemCodecs.createRegistry(json), json, CLOCK);
    }

    @Test
    void 压缩提交等待Thread时不得预先占用Turn或Checkpoint() throws Exception {
        var command = running("compaction-order");
        var request = compaction(command);
        var ticket = journal.recordCompactionIntent(request, false);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor();
                var holder = database.open()) {
            holder.setAutoCommit(false);
            new TurnRepository().lockThread(holder, command.turn().threadId());
            var pending = executor.submit(() -> journal.commitCompaction(request, ticket, outcome(), USAGE));
            try {
                awaitBlocked(holder, "AGENT_THREAD");
                assertTurnAndCheckpointAvailable(command.turn().id());
            } finally {
                holder.rollback();
            }
            pending.get(5, TimeUnit.SECONDS);
        }
        assertEquals(USAGE, journal.readRecovery(command.turn().id()).usage());
    }

    @Test
    void 审批申请和决议等待Turn时尚未占用Checkpoint() throws Exception {
        var command = running("approval-order");
        var request = approval(command.turn());
        CountDownLatch registered = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor();
                var approvals = new ApprovalService(database, json, CLOCK)) {
            approvals.onChanged(record -> {
                if (record.state() == ApprovalState.PENDING) {
                    registered.countDown();
                }
            });
            Future<?> waiting;
            try (var holder = holdTurn(command.turn().id())) {
                waiting = executor.submit(() -> approvals.await(request, new CancellationSource()));
                assertCheckpointWhileBlocked(holder, command.turn().id());
            }
            assertTrue(registered.await(5, TimeUnit.SECONDS));
            var payload = new CoreRpcContracts.ApprovalResolvePayload(request.id(), ApprovalDecision.APPROVED, "允许");
            Future<?> decision;
            try (var holder = holdTurn(command.turn().id())) {
                decision = executor.submit(
                        () -> approvals.resolve(identity("approval/resolve", "resolve", 1, payload), payload));
                assertCheckpointWhileBlocked(holder, command.turn().id());
            }
            decision.get(5, TimeUnit.SECONDS);
            waiting.get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void 终态先提交后迟到的意图增量及结果均不能重新打开公开流() throws Exception {
        for (String operation : List.of("intent", "delta", "result")) {
            var command = running(operation);
            if (!"intent".equals(operation)) {
                journal.recordModelIntent(command.turn().id(), 1, "1".repeat(64));
            }
            assertTerminalWins(command.turn(), operation);
        }
    }

    @Test
    void 已终态Turn拒绝新压缩及迟到压缩结果且保留原始账本() throws Exception {
        var command = running("late-compaction");
        var request = compaction(command);
        var ticket = journal.recordCompactionIntent(request, false);
        journal.transition(command.turn().id(), TurnStatus.RUNNING, TurnStatus.CANCELLED, Optional.empty());
        var streams = new TurnStreamService(database, json);
        var before = streams.watermark(command.turn().id());
        assertThrows(PersistenceException.class, () -> journal.recordCompactionIntent(request, false));
        assertThrows(PersistenceException.class, () -> journal.commitCompaction(request, ticket, outcome(), USAGE));
        assertEquals(before, streams.watermark(command.turn().id()));
        assertEquals(1, core.listItems(command.turn().threadId(), 0, 100).size());
        try (var connection = database.open();
                var query =
                        connection.prepareStatement("SELECT STATE FROM CORE.TURN_COMPACTION_CALL WHERE TURN_ID = ?")) {
            query.setString(1, command.turn().id().toString());
            try (var rows = query.executeQuery()) {
                assertTrue(rows.next());
                assertEquals("IN_FLIGHT", rows.getString(1));
                assertEquals(false, rows.next());
            }
        }
    }

    private void assertTerminalWins(AgentTurn turn, String operation) throws Exception {
        var streams = new TurnStreamService(database, json);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor();
                var holder = database.open()) {
            holder.setAutoCommit(false);
            new TurnStreamRepository(json).lockJournal(holder, turn.id());
            var pending = executor.submit(() -> {
                publish(turn.id(), operation);
                return null;
            });
            try {
                awaitBlocked(holder, "delta".equals(operation) ? "AGENT_TURN" : "AGENT_THREAD");
                new TurnRepository()
                        .transition(holder, turn.id(), TurnStatus.RUNNING, TurnStatus.CANCELLED, Optional.empty(), NOW);
                holder.commit();
            } finally {
                holder.rollback();
            }
            var rejected =
                    assertThrows(java.util.concurrent.ExecutionException.class, () -> pending.get(5, TimeUnit.SECONDS));
            assertInstanceOf(PersistenceException.class, rejected.getCause());
        }
        var terminal = streams.watermark(turn.id());
        assertTrue(terminal.terminal());
        assertThrows(PersistenceException.class, () -> publish(turn.id(), operation));
        assertEquals(terminal, streams.watermark(turn.id()));
        var events = streams.list(new TurnStreamRpcContracts.ListRequest(turn.id(), TurnStreamRpcContracts.START, 128));
        assertEquals(terminal.lastCursor(), events.nextCursor());
        assertEquals(1, core.listItems(turn.threadId(), 0, 100).size());
    }

    private void publish(TurnId turnId, String operation) throws InterruptedException {
        switch (operation) {
            case "intent" -> journal.recordModelIntent(turnId, 1, "1".repeat(64));
            case "delta" ->
                new H2ModelEventSink(database, json, CLOCK)
                        .forInvocation(1)
                        .publish(turnId, new ModelStreamEvent.TextDelta("迟到"), new CancellationSource());
            case "result" -> journal.commitModelResult(turnId, 1, result("迟到", List.of()), USAGE);
            default -> throw new IllegalArgumentException(operation);
        }
    }

    private void assertCheckpointWhileBlocked(Connection holder, TurnId turnId) throws Exception {
        try {
            awaitBlocked(holder, "AGENT_TURN");
            try (var probe = database.open()) {
                probe.setAutoCommit(false);
                lockCheckpoint(probe, turnId);
                probe.rollback();
            }
        } finally {
            holder.rollback();
        }
    }

    private void assertTurnAndCheckpointAvailable(TurnId turnId) throws Exception {
        try (var probe = database.open()) {
            probe.setAutoCommit(false);
            try (var setting = probe.createStatement()) {
                setting.execute("SET LOCK_TIMEOUT 500");
            }
            new TurnRepository().lock(probe, turnId);
            lockCheckpoint(probe, turnId);
            probe.rollback();
        }
    }

    private Connection holdTurn(TurnId turnId) throws Exception {
        Connection connection = database.open();
        connection.setAutoCommit(false);
        new TurnRepository().lock(connection, turnId);
        return connection;
    }

    private void lockCheckpoint(Connection connection, TurnId turnId) throws Exception {
        try (var setting = connection.createStatement()) {
            setting.execute("SET LOCK_TIMEOUT 500");
        }
        try (var lock = connection.prepareStatement(
                "SELECT TURN_ID FROM CORE.TURN_EXECUTION_CHECKPOINT WHERE TURN_ID = ? FOR UPDATE")) {
            lock.setString(1, turnId.toString());
            try (var rows = lock.executeQuery()) {
                assertTrue(rows.next());
            }
        }
    }

    private void awaitBlocked(Connection holder, String table) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        try (var query = holder.prepareStatement(
                "SELECT EXECUTING_STATEMENT FROM INFORMATION_SCHEMA.SESSIONS WHERE BLOCKER_ID = SESSION_ID()")) {
            while (System.nanoTime() < deadline) {
                try (var rows = query.executeQuery()) {
                    if (rows.next() && rows.getString(1).contains(table)) {
                        return;
                    }
                }
                Thread.onSpinWait();
            }
        }
        throw new AssertionError("事务未进入预期 SQL 锁等待: " + table);
    }

    private TurnExecutionCommand running(String suffix) {
        var workspace = core.createWorkspace(
                identity("workspace/create", "ws-" + suffix, 0, suffix), suffix, temporaryDirectory.resolve(suffix));
        var thread = core.createThread(
                identity("thread/create", "thread-" + suffix, 0, suffix),
                workspace.id(),
                Optional.empty(),
                ThreadExecutionIntent.WORKSPACE,
                suffix);
        var message = new CorePayloads.Message(MessageRole.USER, suffix, List.of(), Optional.empty());
        var budget = new TurnBudget(1000, 1000, 4, 0, Duration.ofMinutes(1));
        var turn = core.startTurn(
                identity("turn/start", "turn-" + suffix, 0, suffix),
                TurnContractFixtures.request(thread.id(), budget, message));
        var catalog = json.decode(core.toolCatalogSnapshot(turn.id()), ToolCatalogSnapshot.class);
        var command =
                new TurnExecutionCommand(turn, turn.provider(), "system", suffix, catalog.permissionCeiling(), catalog);
        journal.beginOrRecover(command);
        return command;
    }

    private ApprovalRequest approval(AgentTurn turn) {
        var arguments = json.encode(Map.of("path", "test.txt"));
        var tool = new ToolIdentity("builtin.test", "write", 1);
        var call = new ModelToolCall("call", tool, arguments);
        journal.recordModelIntent(turn.id(), 1, "1".repeat(64));
        journal.commitModelResult(turn.id(), 1, result("", List.of(call)), USAGE);
        journal.recordToolIntent(
                turn.id(),
                0,
                new ToolCallRequest(turn.id(), call.callId(), tool, arguments, "tool-key", 1),
                1,
                "2".repeat(64));
        return new ApprovalRequest(
                "approval",
                turn.id(),
                call.callId(),
                tool,
                ToolRisk.EXTERNAL_EFFECT,
                "写入",
                arguments.sha256(),
                NOW,
                NOW.plusSeconds(60));
    }

    private CommandIdentity identity(String method, String key, long revision, Object value) {
        return CommandIdentity.from(method, new WriteCommand(key, revision, json.encode(Map.of("value", value))), json);
    }

    private static ModelInvocationResult result(String text, List<ModelToolCall> calls) {
        return new ModelInvocationResult(
                text,
                calls,
                USAGE,
                Optional.empty(),
                Optional.empty(),
                calls.isEmpty() ? ModelFinishReason.COMPLETE : ModelFinishReason.TOOL_CALLS);
    }

    private static CompactionRequest compaction(TurnExecutionCommand command) {
        return new CompactionRequest(command, new ConversationWindow(List.of(), Optional.empty(), 100), 50, 0);
    }

    private static CompactionOutcome outcome() {
        return new CompactionOutcome(
                new ConversationWindow(List.of(), Optional.empty(), 20),
                new CorePayloads.Compaction("local", 100, "摘要", Optional.empty()),
                USAGE);
    }
}
