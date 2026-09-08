package com.javaclaw.server.persistence;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.javaclaw.api.AgentTurn;
import com.javaclaw.api.ApprovalState;
import com.javaclaw.api.AttachmentRef;
import com.javaclaw.api.CorePayloads;
import com.javaclaw.api.CoreSchemas;
import com.javaclaw.api.ItemStatus;
import com.javaclaw.api.MessageRole;
import com.javaclaw.api.ThreadExecutionIntent;
import com.javaclaw.api.ThreadId;
import com.javaclaw.api.ToolRisk;
import com.javaclaw.api.TurnBudget;
import com.javaclaw.protocol.CanonicalJson;
import com.javaclaw.protocol.CoreItemCodecs;
import com.javaclaw.protocol.TurnStreamRpcContracts;
import com.javaclaw.protocol.WriteCommand;
import com.javaclaw.server.TurnContractFixtures;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ItemHistoryRepositoryTest {
    @TempDir
    Path directory;

    private final CanonicalJson json = new CanonicalJson();
    private H2Database database;
    private H2TurnJournal journal;
    private TurnStreamService streams;
    private AgentTurn turn;

    @BeforeEach
    void 创建具有真实Item序号的H2历史() {
        database = new H2Database(directory.resolve("data-v6"));
        database.initialize();
        CoreCommandService core = new CoreCommandService(database, json, Clock.systemUTC());
        var workspace = core.createWorkspace(identity("workspace/create", "workspace"), "history", directory);
        var thread = core.createThread(
                identity("thread/create", "thread"),
                workspace.id(),
                Optional.empty(),
                ThreadExecutionIntent.WORKSPACE,
                "history");
        var request = TurnContractFixtures.request(
                thread.id(),
                new TurnBudget(100, 100, 0, 0, Duration.ofMinutes(1)),
                new CorePayloads.Message(MessageRole.USER, "首条", List.of(), Optional.empty()));
        turn = core.startTurn(identity("turn/start", "turn"), request);
        journal = new H2TurnJournal(database, CoreItemCodecs.createRegistry(json), json, Clock.systemUTC());
        streams = new TurnStreamService(database, json);
    }

    @Test
    void 超过单帧的消息只返回摘要且保留精确全文来源和附件() throws Exception {
        AttachmentRef attachment = new AttachmentRef("a".repeat(64), "text/plain", "附件.txt", 100);
        String fullText = "😀".repeat(4_300_000);
        journal.append(
                turn.id(),
                "message",
                CoreSchemas.MESSAGE,
                new CorePayloads.Message(MessageRole.ASSISTANT, fullText, List.of(attachment), Optional.empty()),
                ItemStatus.COMPLETED);
        var item = latestIdentity();
        var page = streams.history(new TurnStreamRpcContracts.ItemHistoryRequest(turn.threadId(), 0, 1));
        var entry = page.items().getFirst();
        assertEquals(item.id(), entry.id());
        assertEquals(4096, entry.summary().length());
        assertTrue(entry.truncated());
        assertEquals(Optional.of(item.id()), entry.bodyReference().orElseThrow().sourceItemId());
        assertEquals(List.of(attachment), entry.attachments());
        assertTrue(json.encode(page).json().getBytes(StandardCharsets.UTF_8).length < 32 * 1024);
        assertEquals(item.sequence(), page.latestSequence());
        assertTrue(page.hasEarlier());
        var earlier =
                streams.history(new TurnStreamRpcContracts.ItemHistoryRequest(turn.threadId(), entry.sequence(), 1));
        assertEquals("首条", earlier.items().getFirst().summary());
        assertFalse(earlier.hasEarlier());
    }

    @Test
    void 倒序查询翻页后呈升序且每条只出现一次() {
        for (int index = 0; index < 8; index++) {
            journal.append(
                    turn.id(),
                    "message",
                    CoreSchemas.MESSAGE,
                    new CorePayloads.Message(MessageRole.ASSISTANT, "消息" + index, List.of(), Optional.empty()),
                    ItemStatus.COMPLETED);
        }
        var latest = streams.history(new TurnStreamRpcContracts.ItemHistoryRequest(turn.threadId(), 0, 3));
        assertEquals(
                List.of("消息5", "消息6", "消息7"),
                latest.items().stream().map(value -> value.summary()).toList());
        var earlier = streams.history(new TurnStreamRpcContracts.ItemHistoryRequest(
                turn.threadId(), latest.items().getFirst().sequence(), 3));
        assertEquals(
                List.of("消息2", "消息3", "消息4"),
                earlier.items().stream().map(value -> value.summary()).toList());
        assertEquals(latest.latestSequence(), earlier.latestSequence());
        assertTrue(latest.hasEarlier());
        assertThrows(
                PersistenceException.class,
                () -> streams.history(new TurnStreamRpcContracts.ItemHistoryRequest(ThreadId.random(), 0, 10)));
    }

    @Test
    void 摘要保留JSON转义且不可公开的角色不签发正文引用() {
        String text = "引号\"换行\n\\😀";
        journal.append(
                turn.id(),
                "message",
                CoreSchemas.MESSAGE,
                new CorePayloads.Message(MessageRole.SYSTEM, text, List.of(), Optional.empty()),
                ItemStatus.COMPLETED);
        var entry = streams.history(new TurnStreamRpcContracts.ItemHistoryRequest(turn.threadId(), 0, 1))
                .items()
                .getFirst();
        assertEquals(text, entry.summary());
        assertFalse(entry.truncated());
        assertTrue(entry.bodyReference().isEmpty());
    }

    private ItemIdentity latestIdentity() throws Exception {
        try (var connection = database.open();
                var statement = connection.prepareStatement(
                        "SELECT ID,SEQUENCE FROM CORE.ITEM WHERE THREAD_ID=? ORDER BY SEQUENCE DESC FETCH FIRST 1 ROW ONLY")) {
            statement.setString(1, turn.threadId().toString());
            try (var rows = statement.executeQuery()) {
                assertTrue(rows.next());
                return new ItemIdentity(com.javaclaw.api.ItemId.parse(rows.getString(1)), rows.getLong(2));
            }
        }
    }

    @Test
    void 多字节结构化摘要仍遵守整页响应预算并保留分页进度() {
        for (int index = 0; index < 100; index++) {
            journal.append(
                    turn.id(),
                    "error",
                    CoreSchemas.ERROR,
                    new CorePayloads.Error("ERROR_" + index, "😀".repeat(3000), false, Instant.EPOCH, Map.of()),
                    ItemStatus.FAILED);
        }
        var page = streams.history(new TurnStreamRpcContracts.ItemHistoryRequest(turn.threadId(), 0, 100));
        assertTrue(page.hasEarlier());
        assertTrue(page.items().size() < 100);
        assertTrue(page.items().stream()
                .allMatch(item -> item.truncated() && item.summary().length() <= 4096));
        assertTrue(json.encode(page).json().getBytes(StandardCharsets.UTF_8).length < 512 * 1024);
        var earlier = streams.history(new TurnStreamRpcContracts.ItemHistoryRequest(
                turn.threadId(), page.items().getFirst().sequence(), 100));
        assertTrue(
                earlier.items().getLast().sequence() < page.items().getFirst().sequence());
    }

    @Test
    void 持久化工具审批和错误通过历史查询保留状态原因且不伪造正文引用() {
        journal.append(
                turn.id(),
                "tool-call",
                CoreSchemas.TOOL_CALL,
                new CorePayloads.ToolCall("read-1", "coding", "file_read", 1, json.parse("{}")),
                ItemStatus.COMPLETED);
        journal.append(
                turn.id(),
                "tool-result",
                CoreSchemas.TOOL_RESULT,
                new CorePayloads.ToolResult("read-1", true, json.parse("{\"value\":\"读取完成\"}"), Optional.empty()),
                ItemStatus.COMPLETED);
        journal.append(
                turn.id(),
                "tool-result",
                CoreSchemas.TOOL_RESULT,
                new CorePayloads.ToolResult(
                        "read-2",
                        false,
                        json.parse("{\"errorCode\":\"DENIED\",\"message\":\"拒绝读取\"}"),
                        Optional.empty()),
                ItemStatus.COMPLETED);
        journal.append(
                turn.id(),
                "approval",
                CoreSchemas.APPROVAL,
                new CorePayloads.Approval("approval-1", "file_read", ToolRisk.READ_ONLY, ApprovalState.DENIED, "用户拒绝"),
                ItemStatus.COMPLETED);
        journal.append(
                turn.id(),
                "error",
                CoreSchemas.ERROR,
                new CorePayloads.Error("MODEL_ENDPOINT_INVALID", "模型配置不可用", false, Instant.EPOCH, Map.of()),
                ItemStatus.FAILED);
        var page = streams.history(new TurnStreamRpcContracts.ItemHistoryRequest(turn.threadId(), 0, 5));
        assertTrue(page.items().get(0).summary().contains("file_read"));
        assertTrue(page.items().get(1).summary().contains("成功"));
        assertTrue(page.items().get(1).summary().contains("读取完成"));
        assertTrue(page.items().get(2).summary().contains("拒绝读取"));
        assertTrue(page.items().get(3).summary().contains("DENIED · 用户拒绝"));
        assertEquals("MODEL_ENDPOINT_INVALID\n模型配置不可用", page.items().get(4).summary());
        assertTrue(page.items().stream()
                .allMatch(item -> item.bodyReference().isEmpty() && item.role().isEmpty()));
        assertTrue(page.hasEarlier());
    }

    private record ItemIdentity(com.javaclaw.api.ItemId id, long sequence) {}

    private CommandIdentity identity(String method, String key) {
        return CommandIdentity.from(method, new WriteCommand(key, 0, json.parse("{}")), json);
    }
}
