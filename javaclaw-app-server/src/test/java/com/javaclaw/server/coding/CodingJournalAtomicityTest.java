package com.javaclaw.server.coding;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.javaclaw.api.AgentTurn;
import com.javaclaw.api.CanonicalPayload;
import com.javaclaw.api.CorePayloads;
import com.javaclaw.api.EffectReceipt;
import com.javaclaw.api.MessageRole;
import com.javaclaw.api.ThreadExecutionIntent;
import com.javaclaw.api.ToolCallRequest;
import com.javaclaw.api.ToolCallResult;
import com.javaclaw.api.ToolCatalogSnapshot;
import com.javaclaw.api.ToolExecutionFact;
import com.javaclaw.protocol.CoreItemCodecs;
import com.javaclaw.runtime.ModelFinishReason;
import com.javaclaw.runtime.ModelInvocationResult;
import com.javaclaw.runtime.ModelToolCall;
import com.javaclaw.runtime.ModelUsage;
import com.javaclaw.runtime.ToolExecutionOutcome;
import com.javaclaw.runtime.TurnExecutionCommand;
import com.javaclaw.runtime.TurnExecutionPhase;
import com.javaclaw.server.persistence.CodingOperationRepository;
import com.javaclaw.server.persistence.H2Transactions;
import com.javaclaw.server.persistence.H2TurnJournal;
import com.javaclaw.server.persistence.PersistenceException;
import com.javaclaw.server.persistence.TurnStartRequest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CodingJournalAtomicityTest {
    @TempDir
    Path temporary;

    @Test
    void 日志末步冲突整体回滚证据和检查点但不回滚已经发生的文件变化() throws Exception {
        try (var fixture = new CodingTestFixture(temporary)) {
            AgentTurn turn = queued(fixture);
            var journal = journal(fixture);
            var request = pending(fixture, journal, turn);
            var operations = new CodingOperationRepository(fixture.database, fixture.json, fixture.clock);
            var intent = new CodingOperationRepository.Intent(
                    "journal-operation",
                    turn.id(),
                    fixture.workspace.id(),
                    request.callId(),
                    "command_run",
                    fixture.root,
                    request.arguments());
            operations.prepare(intent);
            operations.start(intent.id());
            // 外部文件写入先于数据库结果；本测试只要求日志原子性，绝不把 JDBC 回滚当作文件回滚。
            Files.writeString(fixture.root.resolve("external.txt"), "already changed");
            var result = fixture.json.encode(Map.of("exitCode", 0));
            var facts = facts(fixture, intent.id());
            operations.finish(intent.id(), result, facts, true);
            var before = journal.readRecovery(turn.id());
            long itemsBefore = count(fixture, "ITEM", turn);
            var conflicting = outcome(fixture, request, fixture.json.encode(Map.of("exitCode", 9)), facts);
            assertThrows(
                    PersistenceException.class,
                    () -> journal.commitToolResult(turn.id(), 0, request, conflicting, List.of()));
            assertEquals(itemsBefore, count(fixture, "ITEM", turn));
            assertEquals(0, count(fixture, "EFFECT_RECEIPT", turn));
            assertEquals(before, journal(fixture).readRecovery(turn.id()));
            assertEquals("FINISHED", operations.prepare(intent).state());
            assertEquals("already changed", Files.readString(fixture.root.resolve("external.txt")));
            assertTrue(journal.recoverEffect(request).isEmpty());
            var committed = outcome(fixture, request, result, facts);
            journal(fixture).commitToolResult(turn.id(), 0, request, committed, List.of(request.tool()));
            assertEquals("JOURNALED", operations.prepare(intent).state());
            assertEquals(itemsBefore + 4, count(fixture, "ITEM", turn));
            assertEquals(1, count(fixture, "EFFECT_RECEIPT", turn));
            assertEquals(Optional.of(committed.result()), journal(fixture).recoverEffect(request));
            assertEquals(
                    TurnExecutionPhase.READY_FOR_MODEL,
                    journal(fixture).readRecovery(turn.id()).phase());
            assertThrows(
                    PersistenceException.class,
                    () -> journal.commitToolResult(turn.id(), 0, request, committed, List.of()));
            assertEquals(itemsBefore + 4, count(fixture, "ITEM", turn));
        }
    }

    @Test
    void 回执只能由原工具身份参数和Turn恢复() throws Exception {
        try (var fixture = new CodingTestFixture(temporary)) {
            var turn = queued(fixture);
            var journal = journal(fixture);
            var request = pending(fixture, journal, turn);
            var result = fixture.json.encode(Map.of("exitCode", 0));
            journal.commitToolResult(turn.id(), 0, request, outcome(fixture, request, result, List.of()), List.of());
            var otherArguments = new ToolCallRequest(
                    turn.id(),
                    request.callId(),
                    request.tool(),
                    fixture.json.encode(Map.of("different", true)),
                    request.idempotencyKey(),
                    request.expectedCatalogRevision());
            var otherTurn = new ToolCallRequest(
                    fixture.turn.id(),
                    request.callId(),
                    request.tool(),
                    request.arguments(),
                    request.idempotencyKey(),
                    request.expectedCatalogRevision());
            assertThrows(PersistenceException.class, () -> journal.recoverEffect(otherArguments));
            assertThrows(PersistenceException.class, () -> journal.recoverEffect(otherTurn));
            assertEquals(result, journal.recoverEffect(request).orElseThrow().output());
        }
    }

    private static AgentTurn queued(CodingTestFixture fixture) {
        var thread = fixture.core.createThread(
                fixture.identity("thread/create", "journal-thread", Map.of()),
                fixture.workspace.id(),
                Optional.empty(),
                ThreadExecutionIntent.WORKSPACE,
                "Journal");
        var tools = fixture.json.decode(fixture.core.toolCatalogSnapshot(fixture.turn.id()), ToolCatalogSnapshot.class);
        var request = new TurnStartRequest(
                thread.id(),
                fixture.core.resolvedConfig(fixture.turn.id()),
                fixture.root,
                fixture.core.promptSnapshot(fixture.turn.id()),
                tools,
                new CorePayloads.Message(MessageRole.USER, "journal", List.of(), Optional.empty()),
                Optional.empty(),
                Optional.of(fixture.core
                        .codingEnvironments()
                        .frozen(fixture.turn.id())
                        .inherited()));
        return fixture.core.startTurn(fixture.identity("turn/start", "journal-turn", request), request);
    }

    private static H2TurnJournal journal(CodingTestFixture fixture) {
        return new H2TurnJournal(
                fixture.database, CoreItemCodecs.createRegistry(fixture.json), fixture.json, fixture.clock);
    }

    private static ToolCallRequest pending(CodingTestFixture fixture, H2TurnJournal journal, AgentTurn turn) {
        var catalog = fixture.json.decode(fixture.core.toolCatalogSnapshot(turn.id()), ToolCatalogSnapshot.class);
        journal.beginOrRecover(
                new TurnExecutionCommand(turn, turn.provider(), "test", "journal", fixture.permission, catalog));
        var request = fixture.request(turn, "command_run", Map.of("argv", List.of("java", "Main")), "journal-call");
        journal.recordModelIntent(turn.id(), 1, "a".repeat(64));
        journal.commitModelResult(
                turn.id(),
                1,
                new ModelInvocationResult(
                        "",
                        List.of(new ModelToolCall(request.callId(), request.tool(), request.arguments())),
                        ModelUsage.zero(),
                        Optional.empty(),
                        Optional.empty(),
                        ModelFinishReason.TOOL_CALLS),
                ModelUsage.zero());
        journal.recordToolIntent(turn.id(), 0, request, 1, "b".repeat(64));
        return request;
    }

    private static List<ToolExecutionFact> facts(CodingTestFixture fixture, String id) {
        return List.of(
                new ToolExecutionFact(
                        new CorePayloads.Command(id, List.of("java", "Main"), fixture.root, Optional.of(0))),
                new ToolExecutionFact(new CorePayloads.FileChange(
                        Path.of("external.txt"), "create", Optional.empty(), Optional.of("c".repeat(64)))));
    }

    private static ToolExecutionOutcome outcome(
            CodingTestFixture fixture,
            ToolCallRequest request,
            CanonicalPayload result,
            List<ToolExecutionFact> facts) {
        var receipt = new EffectReceipt(
                request.idempotencyKey(),
                request.tool().name(),
                request.arguments().sha256(),
                result.sha256(),
                fixture.clock.instant());
        return new ToolExecutionOutcome(
                new ToolCallResult(request.callId(), true, result, Optional.of(receipt)), List.of(), facts);
    }

    private static long count(CodingTestFixture fixture, String table, AgentTurn turn) throws Exception {
        return new H2Transactions(fixture.database).execute(connection -> {
            try (var statement =
                    connection.prepareStatement("SELECT COUNT(*) FROM CORE." + table + " WHERE TURN_ID=?")) {
                statement.setString(1, turn.id().toString());
                try (var rows = statement.executeQuery()) {
                    rows.next();
                    return rows.getLong(1);
                }
            }
        });
    }
}
