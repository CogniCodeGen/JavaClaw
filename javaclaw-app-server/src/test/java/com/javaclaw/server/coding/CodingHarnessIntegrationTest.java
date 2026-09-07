package com.javaclaw.server.coding;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.javaclaw.api.AgentTurn;
import com.javaclaw.api.CancellationSource;
import com.javaclaw.api.CorePayloads;
import com.javaclaw.api.CoreSchemas;
import com.javaclaw.api.ItemEnvelope;
import com.javaclaw.api.TurnId;
import com.javaclaw.api.TurnStatus;
import com.javaclaw.builtin.contracts.CodingResults;
import com.javaclaw.protocol.CoreRpcContracts;
import com.javaclaw.runtime.TurnExecutionPhase;
import com.javaclaw.server.persistence.H2Transactions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CodingHarnessIntegrationTest {
    @TempDir
    Path temporary;

    @Test
    void 同一会话经真实Harness聊天修改运行失败修复再聊天并提交完整事实() throws Exception {
        var model = new CodingHarnessModel();
        try (var fixture = new CodingHarnessFixture(temporary, model)) {
            Files.writeString(fixture.base.root.resolve(CodingHarnessModel.FILE_NAME), CodingHarnessModel.GOOD_SOURCE);
            var chat = fixture.queued("chat", "你能怎样帮助我理解和修改项目？");
            var first = fixture.harness.execute(chat, new CancellationSource());
            assertEquals(TurnStatus.COMPLETED, first.status(), first.errorCode().toString());
            assertEquals(0, first.toolCalls());
            assertEquals(CodingHarnessModel.INITIAL_CHAT, first.assistantText());
            var coding = fixture.queued("coding", "查看代码，修改测试预期，运行失败后修复并重跑。");
            var completed = fixture.harness.execute(coding, new CancellationSource());
            assertEquals(
                    TurnStatus.COMPLETED,
                    completed.status(),
                    completed.errorCode().toString());
            assertEquals(CodingHarnessModel.FINAL_CHAT, completed.assistantText());
            assertEquals(7, completed.toolCalls());
            assertEquals(chat.turn().threadId(), coding.turn().threadId());
            assertEquals(chat.turn().role(), coding.turn().role());
            assertEquals(chat.turn().permissionProfile(), coding.turn().permissionProfile());
            assertEquals(
                    CodingHarnessModel.GOOD_SOURCE,
                    Files.readString(fixture.base.root.resolve(CodingHarnessModel.FILE_NAME)));
            var items = fixture.base.core.listItems(fixture.thread.id());
            assertFacts(fixture, items, model);
            assertCheckpointAndLedger(fixture, coding.turn().id());
            assertLegacyWire(fixture, items, coding.turn().id());
        }
    }

    private static void assertFacts(CodingHarnessFixture fixture, List<ItemEnvelope> items, CodingHarnessModel model) {
        assertEquals(7, count(items, CoreSchemas.TOOL_CALL));
        assertEquals(7, count(items, CoreSchemas.TOOL_RESULT));
        assertEquals(2, count(items, CoreSchemas.COMMAND));
        assertEquals(2, count(items, CoreSchemas.FILE_CHANGE));
        assertEquals(4, count(items, CoreSchemas.EFFECT_RECEIPT));
        var commands = items.stream()
                .filter(item -> item.schemaId().equals(CoreSchemas.COMMAND))
                .map(item -> fixture.base.json.decode(item.payload(), CorePayloads.Command.class))
                .toList();
        assertEquals(
                List.of(Optional.of(1), Optional.of(0)),
                commands.stream().map(CorePayloads.Command::exitCode).toList());
        var changes = items.stream()
                .filter(item -> item.schemaId().equals(CoreSchemas.FILE_CHANGE))
                .map(item -> fixture.base.json.decode(item.payload(), CorePayloads.FileChange.class))
                .toList();
        assertTrue(changes.stream().allMatch(change -> "update".equals(change.operation())));
        assertEquals(changes.getFirst().afterDigest(), changes.getLast().beforeDigest());
        assertEquals(changes.getFirst().beforeDigest(), changes.getLast().afterDigest());
        var results = items.stream()
                .filter(item -> item.schemaId().equals(CoreSchemas.TOOL_RESULT))
                .map(item -> fixture.base.json.decode(item.payload(), CorePayloads.ToolResult.class))
                .toList();
        var failed = results.stream()
                .filter(result -> result.callId().equals("failure"))
                .findFirst()
                .orElseThrow();
        var success = results.stream()
                .filter(result -> result.callId().equals("success"))
                .findFirst()
                .orElseThrow();
        assertFalse(failed.success());
        assertTrue(success.success());
        assertEquals(model.failedCommand, fixture.base.json.decode(failed.output(), CodingResults.CommandResult.class));
        assertEquals(
                model.successfulCommand, fixture.base.json.decode(success.output(), CodingResults.CommandResult.class));
        for (var result : results) {
            result.receipt().ifPresent(receipt -> assertEquals(result.output().sha256(), receipt.resultDigest()));
        }
    }

    private static void assertCheckpointAndLedger(CodingHarnessFixture fixture, TurnId turn) throws Exception {
        var recovery = fixture.journal.readRecovery(turn);
        assertEquals(TurnExecutionPhase.MODEL_COMMITTED, recovery.phase());
        assertEquals(7, recovery.toolCalls());
        assertEquals(8, recovery.modelInvocations());
        assertEquals(7, recovery.seenCallIds().size());
        assertTrue(recovery.activeIntentDigest().isEmpty());
        assertEquals(CodingHarnessModel.FINAL_CHAT, recovery.assistantText());
        new H2Transactions(fixture.base.database).execute(connection -> {
            try (var statement = connection.prepareStatement(
                    "SELECT STATE,COUNT(*) FROM CORE.CODING_OPERATION WHERE TURN_ID=? GROUP BY STATE")) {
                statement.setString(1, turn.toString());
                try (var rows = statement.executeQuery()) {
                    assertTrue(rows.next());
                    assertEquals("JOURNALED", rows.getString(1));
                    assertEquals(6, rows.getInt(2));
                    assertFalse(rows.next());
                }
            }
            try (var statement =
                    connection.prepareStatement("SELECT COUNT(*) FROM CORE.EFFECT_RECEIPT WHERE TURN_ID=?")) {
                statement.setString(1, turn.toString());
                try (var rows = statement.executeQuery()) {
                    assertTrue(rows.next());
                    assertEquals(4, rows.getInt(1));
                }
            }
            return null;
        });
    }

    private static void assertLegacyWire(CodingHarnessFixture fixture, List<ItemEnvelope> items, TurnId id) {
        // 新能力沿用 v3 的 Item 信封和 Turn DTO；细节留在各自版本化 payload，不增添旧 DTO 字段。
        var page = new CoreRpcContracts.ItemListResult(items, items.getLast().sequence());
        assertEquals(
                page, fixture.base.json.decode(fixture.base.json.encode(page), CoreRpcContracts.ItemListResult.class));
        var turn = fixture.base.core.findTurn(id).orElseThrow();
        assertEquals(TurnStatus.COMPLETED, turn.status());
        assertEquals(turn, fixture.base.json.decode(fixture.base.json.encode(turn), AgentTurn.class));
    }

    private static long count(List<ItemEnvelope> items, String schema) {
        return items.stream().filter(item -> item.schemaId().equals(schema)).count();
    }
}
