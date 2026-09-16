package com.javaclaw.server.persistence;

import java.net.URI;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.javaclaw.api.AgentTurn;
import com.javaclaw.api.AttachmentRef;
import com.javaclaw.api.CanonicalPayload;
import com.javaclaw.api.CorePayloads;
import com.javaclaw.api.CoreSchemas;
import com.javaclaw.api.ItemEnvelope;
import com.javaclaw.api.ItemHistoryEntry;
import com.javaclaw.api.ItemStatus;
import com.javaclaw.api.MessageRole;
import com.javaclaw.api.ThreadExecutionIntent;
import com.javaclaw.api.ThreadId;
import com.javaclaw.api.TurnBudget;
import com.javaclaw.api.Workspace;
import com.javaclaw.builtin.contracts.BrowserContracts;
import com.javaclaw.builtin.contracts.BrowserResult;
import com.javaclaw.builtin.contracts.BuiltinExtensionIds;
import com.javaclaw.protocol.CanonicalJson;
import com.javaclaw.protocol.TurnStreamRpcContracts;
import com.javaclaw.server.TurnContractFixtures;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ItemHistoryBrowserAttachmentsTest {
    @TempDir
    Path directory;

    private final CanonicalJson json = new CanonicalJson();
    private final Clock clock = Clock.systemUTC();
    private H2Transactions transactions;
    private TurnStreamService streams;
    private Workspace workspace;
    private AgentTurn turn;

    @BeforeEach
    void 建立真实H2历史分页() {
        H2Database database = new H2Database(directory.resolve("data-v6"));
        database.initialize();
        transactions = new H2Transactions(database);
        CoreCommandService core = new CoreCommandService(database, json, clock);
        workspace = core.createWorkspace(identity("workspace/create"), "history", directory);
        var thread = core.createThread(
                identity("thread/create"),
                workspace.id(),
                Optional.empty(),
                ThreadExecutionIntent.WORKSPACE,
                "browser-history");
        turn = core.startTurn(
                identity("turn/start"),
                TurnContractFixtures.request(
                        thread.id(),
                        new TurnBudget(100, 100, 0, 0, Duration.ofMinutes(1)),
                        new CorePayloads.Message(MessageRole.USER, "浏览页面", List.of(), Optional.empty())));
        streams = new TurnStreamService(database, json);
    }

    @Test
    void 大型网页正文不会吞掉历史截图附件或使分页响应包含原文() throws Exception {
        AttachmentRef image = new AttachmentRef("a".repeat(64), "image/png", "网页.png", 120);
        call("screenshot", BuiltinExtensionIds.SITE);
        ItemEnvelope item = result("screenshot", true, output(turn.threadId(), image, "页面原文".repeat(200_000), true));
        ItemHistoryEntry summary = latest();
        assertEquals(item.id(), summary.id());
        assertEquals(List.of(image), summary.attachments());
        assertTrue(summary.truncated());
        assertTrue(json.encode(summary).json().length() < 20_000);
        assertTrue(summary.bodyReference().isEmpty());
    }

    @Test
    void 普通下载同样保留附件而失败或其他工具及跨对话结果不会生成入口() throws Exception {
        AttachmentRef file = new AttachmentRef("b".repeat(64), "text/plain", "下载.txt", 50);
        call("download", BuiltinExtensionIds.SITE);
        result("download", true, output(turn.threadId(), file, "", false));
        assertEquals(List.of(file), latest().attachments());
        call("failed", BuiltinExtensionIds.SITE);
        result("failed", false, output(turn.threadId(), file, "", false));
        assertTrue(latest().attachments().isEmpty());
        call("foreign", "external.tool");
        result("foreign", true, output(turn.threadId(), file, "", false));
        assertTrue(latest().attachments().isEmpty());
        call("other-thread", BuiltinExtensionIds.SITE);
        result("other-thread", true, output(ThreadId.random(), file, "", false));
        assertTrue(latest().attachments().isEmpty());
    }

    @Test
    void 重复调用身份不能通过任一成功来源给历史附件授权() throws Exception {
        AttachmentRef file = new AttachmentRef("c".repeat(64), "text/plain", "下载.txt", 10);
        call("duplicate", BuiltinExtensionIds.SITE);
        call("duplicate", "external.tool");
        result("duplicate", true, output(turn.threadId(), file, "", false));
        assertTrue(latest().attachments().isEmpty());
    }

    private BrowserResult output(ThreadId thread, AttachmentRef attachment, String text, boolean screenshot) {
        URI uri = URI.create("https://example.com");
        var owner = new BrowserContracts.Owner(workspace.id(), thread, Optional.empty());
        var lease = new BrowserContracts.AccessLease(
                BrowserContracts.ControlMode.ASSISTANT,
                "lease",
                1,
                clock.instant().plusSeconds(60),
                Set.of(uri));
        var session = new BrowserContracts.SessionView(
                UUID.randomUUID().toString(), owner, BrowserContracts.SessionState.OPEN, lease, List.of());
        var page = new BrowserContracts.PageSnapshot("page", uri, "页面", text, List.of(), List.of());
        var artifact = new BrowserContracts.Artifact(
                new BrowserContracts.FileSpec(attachment.fileName(), attachment.mediaType()), attachment.sizeBytes());
        Optional<BrowserContracts.Frame> frame = screenshot
                ? Optional.of(new BrowserContracts.Frame(
                        "frame", "page", 1, 1, new BrowserContracts.Viewport(100, 100, 0, 0, 1), 100, 100))
                : Optional.empty();
        return new BrowserResult(
                1,
                new BrowserContracts.Observation(session, page, frame, Optional.of(artifact)),
                Optional.of(attachment));
    }

    private void call(String callId, String producer) throws Exception {
        append(
                CoreSchemas.TOOL_CALL,
                json.encode(new CorePayloads.ToolCall(callId, producer, "browser_act", 1, json.parse("{}"))));
    }

    private ItemEnvelope result(String callId, boolean success, BrowserResult output) throws Exception {
        return append(
                CoreSchemas.TOOL_RESULT,
                json.encode(new CorePayloads.ToolResult(callId, success, json.encode(output), Optional.empty())));
    }

    private ItemEnvelope append(String schema, CanonicalPayload payload) throws Exception {
        return transactions.execute(connection -> new ItemRepository(new TurnRepository())
                .append(
                        connection,
                        new ItemRepository.ItemWrite(
                                turn.id(), schema, schema, "core", ItemStatus.COMPLETED, payload, clock.instant())));
    }

    private ItemHistoryEntry latest() {
        return streams.history(new TurnStreamRpcContracts.ItemHistoryRequest(turn.threadId(), 0, 1))
                .items()
                .getFirst();
    }

    private static CommandIdentity identity(String operation) {
        return new CommandIdentity(operation, UUID.randomUUID().toString(), 0, "a".repeat(64));
    }
}
