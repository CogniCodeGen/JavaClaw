package com.javaclaw.server.turn;

import java.net.URI;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.javaclaw.api.AgentTurn;
import com.javaclaw.api.CanonicalPayload;
import com.javaclaw.api.CorePayloads;
import com.javaclaw.api.CoreSchemas;
import com.javaclaw.api.ItemStatus;
import com.javaclaw.api.MessageRole;
import com.javaclaw.api.ThreadExecutionIntent;
import com.javaclaw.api.ThreadId;
import com.javaclaw.api.ToolIdentity;
import com.javaclaw.api.TurnBudget;
import com.javaclaw.api.TurnId;
import com.javaclaw.api.TurnStatus;
import com.javaclaw.builtin.contracts.BrowserCommands;
import com.javaclaw.builtin.contracts.BuiltinExtensionIds;
import com.javaclaw.extension.spi.ConversationEvidencePort;
import com.javaclaw.extension.spi.ExtensionId;
import com.javaclaw.protocol.CanonicalJson;
import com.javaclaw.protocol.CoreItemCodecs;
import com.javaclaw.protocol.CoreRpcContracts;
import com.javaclaw.protocol.WriteCommand;
import com.javaclaw.runtime.ModelUsage;
import com.javaclaw.runtime.TurnExecutionResult;
import com.javaclaw.server.TurnContractFixtures;
import com.javaclaw.server.persistence.CommandIdentity;
import com.javaclaw.server.persistence.CoreCommandService;
import com.javaclaw.server.persistence.H2Database;
import com.javaclaw.server.persistence.H2ManagedExtensionStore;
import com.javaclaw.server.persistence.H2Transactions;
import com.javaclaw.server.persistence.H2TurnJournal;
import com.javaclaw.server.persistence.ServerConversationEvidencePort;
import com.javaclaw.server.persistence.TurnStartRequest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BrowserTurnContinuationTest {
    private static final URI ORIGIN = URI.create("https://browser.example");
    private static final TurnBudget BUDGET = new TurnBudget(1000, 1000, 3, 1, Duration.ofMinutes(1));

    @TempDir
    Path directory;

    private final CanonicalJson json = new CanonicalJson();
    private final MutableClock clock = new MutableClock();
    private final CorePayloads.Message message =
            new CorePayloads.Message(MessageRole.USER, "按原任务继续", List.of(), Optional.empty());
    private H2Database database;
    private CoreCommandService core;
    private H2TurnJournal journal;
    private ThreadId thread;
    private FakeDispatcher dispatcher;
    private BrowserTurnContinuation continuation;
    private TurnExecutionResult result;

    @BeforeEach
    void 真实Core与不会调用模型的调度端口() {
        database = new H2Database(directory.resolve("data-v6"));
        database.initialize();
        core = new CoreCommandService(database, json, clock);
        journal = new H2TurnJournal(database, CoreItemCodecs.createRegistry(json), json, clock);
        var workspace = core.createWorkspace(
                identity("workspace/create", "workspace"), "Browser", directory.resolve("workspace"));
        thread = core.createThread(
                        identity("thread/create", "thread"),
                        workspace.id(),
                        Optional.empty(),
                        ThreadExecutionIntent.WORKSPACE,
                        "Browser")
                .id();
        dispatcher = new FakeDispatcher();
        continuation = new BrowserTurnContinuation(database, core, json, clock);
        continuation.bind(dispatcher, ignored -> result);
    }

    @Test
    void 新Turn只继承剩余预算并且只保留一个用户消息() throws Exception {
        AgentTurn parent = request();
        clock.advance(Duration.ofSeconds(10));
        complete(parent, TurnStatus.COMPLETED, new ModelUsage(200, 100, 50, 0));
        continuation.finished(result);
        assertEquals(1, dispatcher.resumed.size());
        AgentTurn child = core.findTurn(dispatcher.resumed.getFirst()).orElseThrow();
        assertEquals(new TurnBudget(800, 850, 2, 0, Duration.ofSeconds(50)), child.budget());
        assertEquals(parent.id(), core.originalInputTurn(child.id()));
        assertEquals(message, core.turnUserMessage(child.id()));
        assertEquals(1, userItems());
        assertEquals("STARTED", json.textField(latestEvent(), "state").orElseThrow());
        assertFalse(continuation.requested(parent.id()));
        continuation.finished(result);
        assertEquals(1, dispatcher.resumed.size());
    }

    @Test
    void 续接最终答复进入对话证据且原始用户消息只有一份() {
        AgentTurn parent = request();
        complete(parent, TurnStatus.COMPLETED, ModelUsage.zero());
        continuation.finished(result);
        TurnId child = dispatcher.resumed.getFirst();
        journal.transition(child, TurnStatus.QUEUED, TurnStatus.RUNNING, Optional.empty());
        journal.append(
                child,
                "message",
                CoreSchemas.MESSAGE,
                new CorePayloads.Message(MessageRole.ASSISTANT, "浏览器任务最终结果", List.of(), Optional.empty()),
                ItemStatus.COMPLETED);
        journal.transition(child, TurnStatus.RUNNING, TurnStatus.COMPLETED, Optional.empty());
        var evidence = new ServerConversationEvidencePort(database, json);
        var workspace = core.workspaceForThread(thread).id();
        var page = evidence.scan(
                workspace,
                new ConversationEvidencePort.Cursor(0, 0),
                evidence.committedUpperBound(workspace),
                clock.instant().minusSeconds(1),
                200);
        assertEquals(
                1,
                page.evidence().stream()
                        .filter(value -> value.sourceKind() == ConversationEvidencePort.SourceKind.USER_TEXT)
                        .count());
        assertTrue(page.evidence().stream()
                .anyMatch(value -> value.turnId().equals(child)
                        && value.sourceKind() == ConversationEvidencePort.SourceKind.ASSISTANT_TEXT
                        && value.text().equals("浏览器任务最终结果")));
        assertEquals(1, userItems());
    }

    @Test
    void Core已提交但扩展未补链的崩溃先恢复后继不重算过期预算或重新解析配置() throws Exception {
        AgentTurn parent = request();
        complete(parent, TurnStatus.COMPLETED, new ModelUsage(200, 100, 0, 0));
        String digest =
                json.encode(Map.of("parent", parent.id(), "origin", ORIGIN)).sha256();
        var identity = new CommandIdentity("turn/start", "browser-continue:" + parent.id(), 0, digest);
        // 模拟两个持久事务之间硬退出：Core 已提交，扩展仍只有 REQUESTED。
        AgentTurn committed = core.startTurn(
                identity, TurnContractFixtures.request(thread, BUDGET, message).withContinuedFrom(parent.id()), false);
        clock.advance(Duration.ofMinutes(2));
        dispatcher.rejectConfiguration = true;
        BrowserTurnContinuation reopened = new BrowserTurnContinuation(database, core, json, clock);
        reopened.bind(dispatcher, ignored -> result);
        reopened.recoverPending();
        assertEquals(0, dispatcher.resolved);
        assertEquals(List.of(committed.id()), dispatcher.resumed);
        assertEquals("STARTED", json.textField(latestEvent(), "state").orElseThrow());
        assertEquals(1, userItems());
    }

    @Test
    void 预算不足持久报告失败且重启不静默重试() throws Exception {
        AgentTurn parent = request();
        complete(parent, TurnStatus.COMPLETED, new ModelUsage(1000, 0, 0, 0));
        continuation.finished(result);
        assertEquals(0, dispatcher.resolved);
        assertTrue(dispatcher.resumed.isEmpty());
        assertEquals("FAILED", json.textField(latestEvent(), "state").orElseThrow());
        assertTrue(json.textField(latestEvent(), "detail").orElseThrow().contains("预算"));
        var status = currentStatus();
        assertEquals(BrowserCommands.ContinuationFailure.BUDGET_EXHAUSTED, status.reason());
        assertEquals(parent.id(), status.parent());
        assertTrue(status.child().isEmpty());
        assertFalse(continuation.requested(parent.id()));
        BrowserTurnContinuation reopened = new BrowserTurnContinuation(database, core, json, clock);
        reopened.bind(dispatcher, ignored -> result);
        reopened.recoverPending();
        assertEquals(0, dispatcher.resolved);
    }

    @Test
    void 配置撤销或父任务取消都报告状态且不创建续接() throws Exception {
        AgentTurn parent = request();
        dispatcher.rejectConfiguration = true;
        complete(parent, TurnStatus.COMPLETED, ModelUsage.zero());
        continuation.finished(result);
        assertEquals("FAILED", json.textField(latestEvent(), "state").orElseThrow());
        assertTrue(dispatcher.resumed.isEmpty());
        assertEquals(
                BrowserCommands.ContinuationFailure.CONFIGURATION_UNAVAILABLE,
                currentStatus().reason());
        dispatcher.rejectConfiguration = false;
        AgentTurn cancelled = request();
        complete(cancelled, TurnStatus.CANCELLED, ModelUsage.zero());
        continuation.finished(result);
        assertEquals("STOPPED", json.textField(latestEvent(), "state").orElseThrow());
        assertEquals(
                BrowserCommands.ContinuationFailure.PARENT_NOT_COMPLETED,
                currentStatus().reason());
        assertTrue(dispatcher.resumed.isEmpty());
        assertFalse(continuation.requested(cancelled.id()));
    }

    @Test
    void 只有Site浏览器完整身份和受信状态可以请求让出Turn() {
        CanonicalPayload signal = json.encode(Map.of("status", "BROWSER_AUTHORIZATION_CONTINUATION"));
        String browserTool = BrowserCommands.TOOL_NAMES.iterator().next();
        assertTrue(BrowserTurnContinuation.matches(
                json, new ToolIdentity(BuiltinExtensionIds.SITE, browserTool, 1), signal));
        assertFalse(BrowserTurnContinuation.matches(json, new ToolIdentity("third-party", browserTool, 1), signal));
        assertFalse(BrowserTurnContinuation.matches(
                json, new ToolIdentity(BuiltinExtensionIds.SITE, "unrelated", 1), signal));
        var trusted = new ToolIdentity(BuiltinExtensionIds.SITE, browserTool, 1);
        assertFalse(BrowserTurnContinuation.matches(json, trusted, json.encode(Map.of("status", "done"))));
        assertFalse(BrowserTurnContinuation.matches(json, trusted, new CanonicalPayload("{}")));
    }

    @Test
    void 初始化次序误用被拒绝且排队或终态Turn不能申请续接() throws Exception {
        var unbound = new BrowserTurnContinuation(database, core, json, clock);
        assertThrows(IllegalStateException.class, unbound::recoverPending);
        assertThrows(IllegalStateException.class, () -> continuation.bind(dispatcher, ignored -> result));
        continuation.recoverPending();
        var request = TurnContractFixtures.request(thread, BUDGET, message);
        AgentTurn queued = core.startTurn(identity("turn/start", request), request);
        assertThrows(IllegalStateException.class, () -> continuation.request(queued.id(), ORIGIN));
        journal.transition(queued.id(), TurnStatus.QUEUED, TurnStatus.RUNNING, Optional.empty());
        journal.transition(queued.id(), TurnStatus.RUNNING, TurnStatus.WAITING, Optional.empty());
        continuation.request(queued.id(), ORIGIN);
        var pending = currentStatus();
        continuation.request(queued.id(), URI.create("https://another.example"));
        assertEquals(pending, currentStatus());
        journal.transition(queued.id(), TurnStatus.WAITING, TurnStatus.CANCELLED, Optional.empty());
        assertThrows(IllegalStateException.class, () -> continuation.request(queued.id(), ORIGIN));
        assertFalse(continuation.requested(TurnId.random()));
        continuation.finished(new TurnExecutionResult(
                TurnId.random(), TurnStatus.COMPLETED, "", ModelUsage.zero(), 0, Optional.empty(), Optional.empty()));
    }

    @Test
    void 剩余输出工具次数与时限任一耗尽都不解析新配置() throws Exception {
        List<BudgetBoundary> boundaries = List.of(
                new BudgetBoundary(new ModelUsage(0, 1000, 0, 0), 1, Duration.ZERO),
                new BudgetBoundary(ModelUsage.zero(), 3, Duration.ZERO),
                new BudgetBoundary(ModelUsage.zero(), 1, Duration.ofMinutes(1)),
                new BudgetBoundary(ModelUsage.zero(), 1, Duration.ofMinutes(1).plusNanos(1)));
        for (BudgetBoundary boundary : boundaries) {
            AgentTurn parent = request();
            clock.advance(boundary.elapsed());
            complete(parent, TurnStatus.COMPLETED, boundary.usage());
            result = new TurnExecutionResult(
                    parent.id(),
                    TurnStatus.COMPLETED,
                    "",
                    boundary.usage(),
                    boundary.tools(),
                    Optional.empty(),
                    Optional.empty());
            continuation.finished(result);
            assertEquals(
                    BrowserCommands.ContinuationFailure.BUDGET_EXHAUSTED,
                    currentStatus().reason());
            assertTrue(currentStatus().child().isEmpty());
        }
        assertEquals(0, dispatcher.resolved);
        assertTrue(dispatcher.resumed.isEmpty());
    }

    @Test
    void 墙钟回拨不会给后继增加原任务的时限预算() {
        AgentTurn parent = request();
        complete(parent, TurnStatus.COMPLETED, ModelUsage.zero());
        clock.advance(Duration.ofSeconds(-10));
        continuation.finished(result);
        var child = core.findTurn(dispatcher.resumed.getFirst()).orElseThrow();
        assertEquals(BUDGET.wallTime(), child.budget().wallTime());
        assertEquals(1000, child.budget().inputTokens());
    }

    @Test
    void 已提交后继调度失败仍记录后继身份且重启不再调度() throws Exception {
        AgentTurn parent = request();
        dispatcher.rejectResume = true;
        complete(parent, TurnStatus.COMPLETED, ModelUsage.zero());
        continuation.finished(result);
        var failed = currentStatus();
        assertEquals(BrowserCommands.ContinuationFailure.CONFIGURATION_UNAVAILABLE, failed.reason());
        assertEquals(dispatcher.resumed.getFirst(), failed.child().orElseThrow());
        assertEquals(parent.id(), core.originalInputTurn(failed.child().orElseThrow()));
        var reopened = new BrowserTurnContinuation(database, core, json, clock);
        reopened.bind(dispatcher, ignored -> result);
        reopened.recoverPending();
        assertEquals(1, dispatcher.resumed.size());
        assertEquals(1, userItems());
    }

    @Test
    void 后继调度立即失败或取消都持久呈现失败而不是已启动() throws Exception {
        for (TurnStatus terminal : List.of(TurnStatus.FAILED, TurnStatus.CANCELLED)) {
            AgentTurn parent = request();
            dispatcher.resumeTerminal = Optional.of(terminal);
            complete(parent, TurnStatus.COMPLETED, ModelUsage.zero());
            continuation.finished(result);
            var failed = currentStatus();
            assertEquals(BrowserCommands.ContinuationState.FAILED, failed.state());
            assertEquals(BrowserCommands.ContinuationFailure.CONFIGURATION_UNAVAILABLE, failed.reason());
            assertEquals(
                    terminal,
                    core.findTurn(failed.child().orElseThrow()).orElseThrow().status());
        }
    }

    @Test
    void 已提交运行中后继可以恢复但等待输入和已完成后继不重复调度() throws Exception {
        for (TurnStatus status : List.of(TurnStatus.RUNNING, TurnStatus.WAITING, TurnStatus.COMPLETED)) {
            AgentTurn parent = request();
            complete(parent, TurnStatus.COMPLETED, ModelUsage.zero());
            AgentTurn child = commitChild(parent, status);
            int before = dispatcher.resumed.size();
            continuation.finished(result);
            assertEquals(before + (status == TurnStatus.RUNNING ? 1 : 0), dispatcher.resumed.size());
            assertEquals(
                    BrowserCommands.ContinuationState.STARTED, currentStatus().state());
            assertEquals(child.id(), currentStatus().child().orElseThrow());
            if (status != TurnStatus.COMPLETED) {
                journal.transition(child.id(), status, TurnStatus.CANCELLED, Optional.empty());
            }
        }
        assertEquals(0, dispatcher.resolved);
    }

    @Test
    void 旧持久请求缺省状态能恢复但父任务权威终态不会被调用者结果覆盖() throws Exception {
        AgentTurn parent = request();
        var store = new H2ManagedExtensionStore(database, clock);
        store.inTransaction(new ExtensionId(BuiltinExtensionIds.SITE), tx -> {
            var current =
                    tx.get("browser.continuations", parent.id().toString()).orElseThrow();
            tx.put(
                    "browser.continuations",
                    parent.id().toString(),
                    current.revision(),
                    json.encode(Map.of("parent", parent.id(), "origin", ORIGIN, "child", Optional.empty())));
            return null;
        });
        var reopened = new BrowserTurnContinuation(database, core, json, clock);
        reopened.bind(dispatcher, ignored -> result);
        assertTrue(reopened.requested(parent.id()));
        complete(parent, TurnStatus.CANCELLED, ModelUsage.zero());
        result = new TurnExecutionResult(
                parent.id(), TurnStatus.COMPLETED, "", ModelUsage.zero(), 0, Optional.empty(), Optional.empty());
        reopened.recoverPending();
        assertEquals(BrowserCommands.ContinuationState.STOPPED, currentStatus().state());
        assertEquals(
                BrowserCommands.ContinuationFailure.PARENT_NOT_COMPLETED,
                currentStatus().reason());
        assertTrue(dispatcher.resumed.isEmpty());
    }

    private AgentTurn commitChild(AgentTurn parent, TurnStatus status) {
        String digest =
                json.encode(Map.of("parent", parent.id(), "origin", ORIGIN)).sha256();
        var identity = new CommandIdentity("turn/start", "browser-continue:" + parent.id(), 0, digest);
        AgentTurn child = core.startTurn(
                identity, TurnContractFixtures.request(thread, BUDGET, message).withContinuedFrom(parent.id()));
        journal.transition(child.id(), TurnStatus.QUEUED, TurnStatus.RUNNING, Optional.empty());
        if (status != TurnStatus.RUNNING) {
            journal.transition(child.id(), TurnStatus.RUNNING, status, Optional.empty());
        }
        return child;
    }

    private record BudgetBoundary(ModelUsage usage, int tools, Duration elapsed) {}

    private AgentTurn request() {
        var request = TurnContractFixtures.request(thread, BUDGET, message);
        AgentTurn parent = core.startTurn(identity("turn/start", request), request);
        journal.transition(parent.id(), TurnStatus.QUEUED, TurnStatus.RUNNING, Optional.empty());
        continuation.request(parent.id(), ORIGIN);
        assertTrue(continuation.requested(parent.id()));
        return parent;
    }

    private void complete(AgentTurn parent, TurnStatus status, ModelUsage usage) {
        journal.transition(parent.id(), TurnStatus.RUNNING, status, Optional.empty());
        result = new TurnExecutionResult(parent.id(), status, "", usage, 1, Optional.empty(), Optional.empty());
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

    private BrowserCommands.ContinuationStatus currentStatus() throws Exception {
        var store = new H2ManagedExtensionStore(database, clock);
        var workspace = core.workspaceForThread(thread).id();
        return store.inTransaction(
                new ExtensionId(BuiltinExtensionIds.SITE),
                tx -> json.decode(
                        tx.get("browser.continuation-status." + workspace, thread.toString())
                                .orElseThrow()
                                .payload(),
                        BrowserCommands.ContinuationStatus.class));
    }

    private CanonicalPayload latestEvent() throws Exception {
        return new H2Transactions(database).execute(connection -> {
            try (var statement = connection.createStatement();
                    var rows = statement.executeQuery(
                            "SELECT PAYLOAD FROM CORE.EVENT WHERE TOPIC='site.browser.continuation.changed' ORDER BY SEQUENCE DESC LIMIT 1")) {
                assertTrue(rows.next());
                return new CanonicalPayload(rows.getString(1));
            }
        });
    }

    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-09-14T00:00:00Z");

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    private final class FakeDispatcher implements TurnDispatcher {
        private final List<TurnId> resumed = new ArrayList<>();
        private int resolved;
        private boolean rejectConfiguration;
        private boolean rejectResume;
        private Optional<TurnStatus> resumeTerminal = Optional.empty();

        @Override
        public TurnStartRequest resolve(CoreRpcContracts.TurnStartPayload request, CorePayloads.Message input) {
            resolved++;
            if (rejectConfiguration) {
                throw new SecurityException("配置已撤销");
            }
            return TurnContractFixtures.request(
                    request.threadId(), request.execution().budget().orElseThrow(), input);
        }

        @Override
        public void dispatch(AgentTurn turn, CoreRpcContracts.TurnStartPayload request) {
            resumed.add(turn.id());
        }

        @Override
        public void resume(TurnId turnId) {
            resumed.add(turnId);
            if (rejectResume) {
                throw new IllegalStateException("调度端口不可用");
            }
            if (resumeTerminal.isPresent()) {
                journal.transition(turnId, TurnStatus.QUEUED, TurnStatus.RUNNING, Optional.empty());
                journal.transition(turnId, TurnStatus.RUNNING, resumeTerminal.orElseThrow(), Optional.empty());
            }
        }

        @Override
        public void cancel(TurnId turnId, String reason) {}
    }
}
