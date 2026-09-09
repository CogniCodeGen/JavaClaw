package com.javaclaw.server.persistence;

import java.nio.file.Files;
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
import com.javaclaw.api.TurnStatus;
import com.javaclaw.api.Workspace;
import com.javaclaw.protocol.CanonicalJson;
import com.javaclaw.server.TurnContractFixtures;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ThreadAutomaticTitleTest {
    private static final Instant CREATED = Instant.parse("2026-09-09T10:00:00Z");
    private static final Instant SENT = CREATED.plusSeconds(10);
    private final CanonicalJson json = new CanonicalJson();

    @TempDir
    Path temporaryDirectory;

    private H2Database database;
    private CoreCommandService core;
    private Workspace workspace;

    @BeforeEach
    void initialize() throws Exception {
        database = new H2Database(temporaryDirectory.resolve("data-v6"));
        database.initialize();
        core = service(CREATED);
        workspace = core.createWorkspace(
                identity("workspace/create", "workspace"),
                "测试项目",
                Files.createDirectories(temporaryDirectory.resolve("workspace")));
    }

    @Test
    void 首次发送同事务保存简洁标题且原消息幂等回执和重启后数据保持() {
        ConversationThread thread = thread("root", "新对话", Optional.empty());
        String message = "## **请分析输入卡顿**\n并优化聊天界面";
        AgentTurn first = start(thread, "first", message, MessageRole.USER);
        ConversationThread named = core.findThread(thread.id()).orElseThrow();
        assertEquals("请分析输入卡顿 并优化聊天界面", named.title());
        assertEquals(3, named.revision());
        assertEquals(CREATED, named.createdAt());
        assertEquals(SENT, named.updatedAt());
        assertEquals(message, core.turnUserMessage(first.id()).text());
        assertEquals(1, core.listItems(thread.id()).size());
        assertEquals(first, start(thread, "first", message, MessageRole.USER));
        assertEquals(named, core.findThread(thread.id()).orElseThrow());
        H2Database reopened = new H2Database(database.dataRoot());
        reopened.initialize();
        CoreCommandService restarted = new CoreCommandService(reopened, json, Clock.fixed(SENT, ZoneOffset.UTC));
        assertEquals(named, restarted.findThread(thread.id()).orElseThrow());
        assertEquals(message, restarted.turnUserMessage(first.id()).text());
    }

    @Test
    void 后续消息不会重新生成标题或覆盖用户自定义名称() throws Exception {
        ConversationThread thread = thread("root", "新对话", Optional.empty());
        AgentTurn first = start(thread, "first", "第一个主题", MessageRole.USER);
        finish(first);
        start(thread, "second", "完全不同的第二个主题", MessageRole.USER);
        assertEquals("第一个主题", core.findThread(thread.id()).orElseThrow().title());
        assertEquals(4, core.findThread(thread.id()).orElseThrow().revision());
        ConversationThread custom = thread("custom", "我指定的标题", Optional.empty());
        start(custom, "custom-first", "不能覆盖标题", MessageRole.USER);
        assertEquals("我指定的标题", core.findThread(custom.id()).orElseThrow().title());
        assertEquals(2, core.findThread(custom.id()).orElseThrow().revision());
    }

    @Test
    void 子任务和非用户首条消息不触发自动命名() {
        ConversationThread root = thread("parent", "父对话", Optional.empty());
        ConversationThread child = thread("child", "新对话", Optional.of(root.id()));
        start(child, "child-first", "子任务内部请求", MessageRole.USER);
        assertEquals("新对话", core.findThread(child.id()).orElseThrow().title());
        ConversationThread assistant = thread("assistant", "新对话", Optional.empty());
        start(assistant, "assistant-first", "系统内部消息", MessageRole.ASSISTANT);
        assertEquals("新对话", core.findThread(assistant.id()).orElseThrow().title());
    }

    @Test
    void 空白首条消息保持默认且第二条消息不能补写标题() throws Exception {
        ConversationThread thread = thread("blank", "新对话", Optional.empty());
        AgentTurn first = start(thread, "blank-first", " \n\t", MessageRole.USER);
        assertEquals("新对话", core.findThread(thread.id()).orElseThrow().title());
        finish(first);
        start(thread, "blank-second", "第二条消息", MessageRole.USER);
        assertEquals("新对话", core.findThread(thread.id()).orElseThrow().title());
    }

    @Test
    void 幂等回执写入失败时标题消息和Turn一起回滚且可重新发送() {
        ConversationThread thread = thread("rollback", "新对话", Optional.empty());
        assertThrows(PersistenceException.class, () -> start(thread, "x".repeat(201), "不应提前显示的标题", MessageRole.USER));
        assertEquals(thread, core.findThread(thread.id()).orElseThrow());
        assertTrue(core.listItems(thread.id()).isEmpty());
        assertTrue(core.listRecoverableTurns().isEmpty());
        AgentTurn retry = start(thread, "retry", "重新提交成功", MessageRole.USER);
        assertEquals("重新提交成功", core.findThread(thread.id()).orElseThrow().title());
        assertEquals(1, core.listItems(thread.id()).getFirst().sequence());
        assertEquals("重新提交成功", core.turnUserMessage(retry.id()).text());
    }

    private ConversationThread thread(String key, String title, Optional<ThreadId> parent) {
        return core.createThread(
                identity("thread/create", key),
                workspace.id(),
                parent,
                parent.isPresent() ? ThreadExecutionIntent.READ_ONLY : ThreadExecutionIntent.WORKSPACE,
                title);
    }

    private AgentTurn start(ConversationThread thread, String key, String text, MessageRole role) {
        CorePayloads.Message message = new CorePayloads.Message(role, text, List.of(), Optional.empty());
        TurnBudget budget = new TurnBudget(4000, 1000, 2, 0, Duration.ofMinutes(1));
        TurnStartRequest request = TurnContractFixtures.request(
                thread.id(),
                new TurnContractFixtures.Selection(
                        budget,
                        TurnContractFixtures.ROLE,
                        TurnContractFixtures.PROVIDER,
                        TurnContractFixtures.PERMISSIONS),
                workspace.root(),
                TurnContractFixtures.PROMPT_SNAPSHOT,
                TurnContractFixtures.TOOL_CATALOG,
                message,
                Optional.empty());
        return service(SENT).startTurn(identity("turn/start", key), request);
    }

    private void finish(AgentTurn turn) throws Exception {
        try (var connection = database.open()) {
            new TurnRepository()
                    .transition(connection, turn.id(), TurnStatus.QUEUED, TurnStatus.CANCELLED, Optional.empty(), SENT);
        }
    }

    private CoreCommandService service(Instant instant) {
        return new CoreCommandService(database, json, Clock.fixed(instant, ZoneOffset.UTC));
    }

    private static CommandIdentity identity(String method, String key) {
        return new CommandIdentity(method, key, 0, "a".repeat(64));
    }
}
