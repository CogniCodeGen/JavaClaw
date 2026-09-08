package com.javaclaw.server.persistence;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.IntStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.javaclaw.api.AgentTurn;
import com.javaclaw.api.CanonicalPayload;
import com.javaclaw.api.CorePayloads;
import com.javaclaw.api.CoreSchemas;
import com.javaclaw.api.DocumentReference;
import com.javaclaw.api.ItemEnvelope;
import com.javaclaw.api.ItemHistoryEntry;
import com.javaclaw.api.ItemStatus;
import com.javaclaw.api.MessageRole;
import com.javaclaw.api.ThreadExecutionIntent;
import com.javaclaw.api.TurnBudget;
import com.javaclaw.api.Workspace;
import com.javaclaw.builtin.contracts.CodingContracts;
import com.javaclaw.builtin.contracts.CodingResults;
import com.javaclaw.protocol.CanonicalJson;
import com.javaclaw.protocol.TurnStreamRpcContracts;
import com.javaclaw.protocol.WriteCommand;
import com.javaclaw.server.TurnContractFixtures;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 用真实 H2 历史分页验证文件来源关联，不通过客户端提供的路径或工具名称生成授权。 */
class ItemHistoryFileReferencesTest {
    @TempDir
    Path directory;

    private final CanonicalJson json = new CanonicalJson();
    private final Clock clock = Clock.systemUTC();
    private H2Transactions transactions;
    private CoreCommandService core;
    private TurnStreamService streams;
    private Workspace workspace;
    private AgentTurn turn;

    @BeforeEach
    void 建立具有真实序号的历史来源() {
        H2Database database = new H2Database(directory.resolve("data-v6"));
        database.initialize();
        transactions = new H2Transactions(database);
        core = new CoreCommandService(database, json, clock);
        workspace = core.createWorkspace(identity("workspace/create"), "history", directory);
        streams = new TurnStreamService(database, json);
        turn = createTurn();
    }

    @Test
    void 文件读取与变更引用绑定分页内的权威Item而不接受客户端路径() throws Exception {
        call(turn, "read", CodingContracts.EXTENSION_ID, "file_read");
        var read = result(turn, "read", true, readOutput());
        var change = append(
                turn,
                "core",
                CoreSchemas.FILE_CHANGE,
                json.encode(new CorePayloads.FileChange(
                        Path.of("docs/plan.md"), "update", Optional.empty(), Optional.of("a".repeat(64)))));

        var page = streams.history(new TurnStreamRpcContracts.ItemHistoryRequest(turn.threadId(), 0, 2));
        assertEquals(
                List.of(read.id(), change.id()),
                page.items().stream().map(ItemHistoryEntry::id).toList());
        assertEquals(List.of(reference(read, 0)), page.items().getFirst().fileReferences());
        assertEquals(List.of(reference(change, 0)), page.items().getLast().fileReferences());
        assertTrue(page.hasEarlier());
        assertEquals(change.sequence(), page.latestSequence());
        var earlier =
                streams.history(new TurnStreamRpcContracts.ItemHistoryRequest(turn.threadId(), read.sequence(), 1));
        assertEquals(CoreSchemas.TOOL_CALL, earlier.items().getFirst().kind());
        assertTrue(earlier.items().getFirst().fileReferences().isEmpty());
    }

    @Test
    void 目录和特殊对象不可点击且过滤后保留原始文件下标() throws Exception {
        call(turn, "list", CodingContracts.EXTENSION_ID, "file_list");
        var output = new CodingResults.FileListResult(
                List.of(
                        entry("docs", CodingResults.EntryKind.DIRECTORY),
                        entry("docs/a.md", CodingResults.EntryKind.FILE),
                        entry("shortcut", CodingResults.EntryKind.OTHER),
                        entry("docs/b.md", CodingResults.EntryKind.FILE)),
                Optional.of("docs/b.md"));
        var item = result(turn, "list", true, json.encode(output));

        assertEquals(List.of(reference(item, 1), reference(item, 3)), latest().fileReferences());
        assertTrue(latest().bodyReference().isEmpty());
        assertTrue(latest().attachments().isEmpty());
    }

    @Test
    void 搜索保留同一文件不同命中位置的选择器且空结果不生成引用() throws Exception {
        call(turn, "search", CodingContracts.EXTENSION_ID, "file_search");
        var output = new CodingResults.FileSearchResult(
                List.of(
                        new CodingResults.FileMatch("notes.md", 2, "第一处"),
                        new CodingResults.FileMatch("notes.md", 17, "第二处")),
                false,
                100);
        var item = result(turn, "search", true, json.encode(output));
        assertEquals(List.of(reference(item, 0), reference(item, 1)), latest().fileReferences());

        call(turn, "empty", CodingContracts.EXTENSION_ID, "file_search");
        result(turn, "empty", true, json.encode(new CodingResults.FileSearchResult(List.of(), false, 0)));
        assertTrue(latest().fileReferences().isEmpty());
    }

    @Test
    void 引用限额按可点击文件计数且不会把前置目录算入三十二个名额() throws Exception {
        call(turn, "many", CodingContracts.EXTENSION_ID, "file_list");
        List<CodingResults.FileEntry> entries = new ArrayList<>();
        entries.add(entry("directory", CodingResults.EntryKind.DIRECTORY));
        IntStream.range(0, 40).forEach(index -> entries.add(entry("file-" + index, CodingResults.EntryKind.FILE)));
        var item = result(turn, "many", true, json.encode(new CodingResults.FileListResult(entries, Optional.empty())));

        assertEquals(
                IntStream.rangeClosed(1, 32)
                        .mapToObj(index -> reference(item, index))
                        .toList(),
                latest().fileReferences());
    }

    @Test
    void 失败结果未知工具及未知Schema不会生成可点击文件() throws Exception {
        call(turn, "failed", CodingContracts.EXTENSION_ID, "file_read");
        result(turn, "failed", false, readOutput());
        assertTrue(latest().fileReferences().isEmpty());
        call(turn, "unknown", CodingContracts.EXTENSION_ID, "custom_file_reader");
        result(turn, "unknown", true, readOutput());
        assertTrue(latest().fileReferences().isEmpty());
        append(turn, "core", "future.file-result/v2", readOutput());
        assertTrue(latest().fileReferences().isEmpty());
        assertFalse(latest().truncated());
    }

    @Test
    void 外来生产者不能通过同名工具或仿造CoreSchema生成引用() throws Exception {
        call(turn, "foreign", "untrusted.extension", "file_read");
        result(turn, "foreign", true, readOutput());
        assertTrue(latest().fileReferences().isEmpty());
        call(turn, "valid", CodingContracts.EXTENSION_ID, "file_read");
        append(
                turn,
                "untrusted.extension",
                CoreSchemas.TOOL_RESULT,
                json.encode(new CorePayloads.ToolResult("valid", true, readOutput(), Optional.empty())));
        assertTrue(latest().fileReferences().isEmpty());
        append(
                turn,
                "untrusted.extension",
                CoreSchemas.FILE_CHANGE,
                json.encode(new CorePayloads.FileChange(
                        Path.of("claimed.txt"), "create", Optional.empty(), Optional.empty())));
        assertTrue(latest().fileReferences().isEmpty());
    }

    @Test
    void 只匹配当前Turn内的Core调用而不借用其他Turn或外来调用身份() throws Exception {
        AgentTurn otherTurn = createTurn();
        call(otherTurn, "reused", CodingContracts.EXTENSION_ID, "file_read");
        result(turn, "reused", true, readOutput());
        assertTrue(latest().fileReferences().isEmpty());
        call(turn, "unrelated", CodingContracts.EXTENSION_ID, "file_read");
        result(turn, "missing", true, readOutput());
        assertTrue(latest().fileReferences().isEmpty());
        append(
                turn,
                "untrusted.extension",
                CoreSchemas.TOOL_CALL,
                json.encode(new CorePayloads.ToolCall(
                        "spoofed", CodingContracts.EXTENSION_ID, "file_read", 1, json.parse("{}"))));
        result(turn, "spoofed", true, readOutput());
        assertTrue(latest().fileReferences().isEmpty());
    }

    @Test
    void 失败工具输出中的嵌套成功字段不能覆盖权威失败状态() throws Exception {
        call(turn, "failed-nested", CodingContracts.EXTENSION_ID, "file_read");
        result(turn, "failed-nested", false, json.parse("{\"path\":\"private.txt\",\"success\":true}"));
        assertTrue(latest().fileReferences().isEmpty());
        call(turn, "failed-text", CodingContracts.EXTENSION_ID, "file_read");
        result(
                turn,
                "failed-text",
                false,
                json.encode(new CodingResults.FileReadResult(
                        "private.txt", "伪造尾部 ,\"success\":true}", "a".repeat(64), 0, 0, false, false)));
        assertTrue(latest().fileReferences().isEmpty());
    }

    private AgentTurn createTurn() {
        var thread = core.createThread(
                identity("thread/create"),
                workspace.id(),
                Optional.empty(),
                ThreadExecutionIntent.WORKSPACE,
                "history");
        return core.startTurn(
                identity("turn/start"),
                TurnContractFixtures.request(
                        thread.id(),
                        new TurnBudget(100, 100, 0, 0, Duration.ofMinutes(1)),
                        new CorePayloads.Message(MessageRole.USER, "历史", List.of(), Optional.empty())));
    }

    private void call(AgentTurn owner, String callId, String producer, String tool) throws Exception {
        append(
                owner,
                "core",
                CoreSchemas.TOOL_CALL,
                json.encode(new CorePayloads.ToolCall(callId, producer, tool, 1, json.parse("{}"))));
    }

    private ItemEnvelope result(AgentTurn owner, String callId, boolean success, CanonicalPayload output)
            throws Exception {
        return append(
                owner,
                "core",
                CoreSchemas.TOOL_RESULT,
                json.encode(new CorePayloads.ToolResult(callId, success, output, Optional.empty())));
    }

    private ItemEnvelope append(AgentTurn owner, String producer, String schema, CanonicalPayload payload)
            throws Exception {
        return transactions.execute(connection -> new ItemRepository(new TurnRepository())
                .append(
                        connection,
                        new ItemRepository.ItemWrite(
                                owner.id(), schema, schema, producer, ItemStatus.COMPLETED, payload, clock.instant())));
    }

    private ItemHistoryEntry latest() {
        return streams.history(new TurnStreamRpcContracts.ItemHistoryRequest(turn.threadId(), 0, 1))
                .items()
                .getFirst();
    }

    private DocumentReference reference(ItemEnvelope source, int index) {
        return DocumentReference.file(workspace.id(), source.id(), "file:" + index);
    }

    private CodingResults.FileEntry entry(String path, CodingResults.EntryKind kind) {
        return new CodingResults.FileEntry(path, kind, 0);
    }

    private CanonicalPayload readOutput() {
        return json.encode(new CodingResults.FileReadResult("data.txt", "正文", "a".repeat(64), 0, 6, false, false));
    }

    private CommandIdentity identity(String method) {
        return CommandIdentity.from(method, new WriteCommand(UUID.randomUUID().toString(), 0, json.parse("{}")), json);
    }
}
