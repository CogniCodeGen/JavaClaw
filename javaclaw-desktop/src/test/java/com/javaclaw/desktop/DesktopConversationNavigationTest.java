package com.javaclaw.desktop;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.Test;

import com.javaclaw.api.AgentTurn;
import com.javaclaw.api.ConversationThread;
import com.javaclaw.api.CorePayloads;
import com.javaclaw.api.CoreSchemas;
import com.javaclaw.api.ItemEnvelope;
import com.javaclaw.api.ItemHistoryEntry;
import com.javaclaw.api.ItemHistoryResult;
import com.javaclaw.api.ItemId;
import com.javaclaw.api.ItemStatus;
import com.javaclaw.api.MessageRole;
import com.javaclaw.api.ThreadId;
import com.javaclaw.api.TurnId;
import com.javaclaw.api.TurnStatus;
import com.javaclaw.api.Workspace;
import com.javaclaw.api.WorkspaceId;
import com.javaclaw.desktop.state.DesktopState;
import com.javaclaw.protocol.CanonicalJson;
import com.javaclaw.protocol.CoreRpcContracts;
import com.javaclaw.protocol.JsonRpcRequest;
import com.javaclaw.protocol.TurnStreamRpcContracts;
import com.javaclaw.protocol.WriteCommand;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 所有 UI 提交按队列串行执行；RPC 闸门制造真实迟到响应，避免直接调度掩盖导航竞争。 */
class DesktopConversationNavigationTest {
    @Test
    void 重复选择正在运行的同一会话不释放订阅或丢失活动状态() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.start();
            fixture.presenter.selectThread(fixture.first);
            fixture.drain();
            assertTrue(fixture.state().interaction().busy());
            assertTrue(fixture.state().threads().activeTurn().isPresent());
            assertEquals(0, fixture.server.streamReleases.get());
            assertEquals(1, fixture.subscriptions.get());
        }
    }

    @Test
    void 切换到其他会话可操作且返回时恢复已有Turn而不重复启动() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.start();
            fixture.presenter.selectThread(fixture.second);
            fixture.await(() -> fixture.selected(fixture.second)
                    && !fixture.state().interaction().busy());
            assertTrue(fixture.state().threads().activeTurn().isEmpty());
            fixture.presenter.selectThread(fixture.first);
            fixture.await(() -> fixture.selected(fixture.first)
                    && fixture.state().threads().activeTurn().isPresent()
                    && fixture.subscriptions.get() == 2);
            assertTrue(fixture.state().interaction().busy());
            assertEquals(1, fixture.server.turnStarts.get());
            assertEquals(0, fixture.server.turnCancels.get());
        }
    }

    @Test
    void 启动回执晚于切换时不污染新会话并在返回时读取已启动Turn() throws Exception {
        try (Fixture fixture = new Fixture()) {
            CountDownLatch entered = new CountDownLatch(1);
            CountDownLatch released = new CountDownLatch(1);
            fixture.beforeStartReply = () -> {
                entered.countDown();
                awaitLatch(released);
            };
            fixture.presenter.send("迟到启动");
            fixture.drain();
            assertTrue(entered.await(3, TimeUnit.SECONDS));
            assertEquals(
                    "迟到启动",
                    fixture.state().transcript().outgoing().orElseThrow().text());
            fixture.presenter.selectThread(fixture.second);
            fixture.drain();
            assertTrue(fixture.state().transcript().outgoing().isEmpty());
            released.countDown();
            fixture.await(() -> fixture.selected(fixture.second) && fixture.server.historyReads.get() >= 2);
            assertFalse(fixture.state().interaction().busy());
            assertTrue(fixture.state().threads().activeTurn().isEmpty());
            assertTrue(fixture.state().transcript().outgoing().isEmpty());
            assertEquals(0, fixture.subscriptions.get());
            fixture.presenter.selectThread(fixture.first);
            fixture.await(() -> fixture.subscriptions.get() == 1);
            assertEquals(1, fixture.server.turnStarts.get());
        }
    }

    @Test
    void 离开后已完成的会话返回时恢复输入且不订阅终态() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.start();
            fixture.presenter.selectThread(fixture.second);
            fixture.await(() -> fixture.selected(fixture.second)
                    && !fixture.state().interaction().busy());
            fixture.turns.put(fixture.first.id(), fixture.turn(fixture.first, TurnStatus.COMPLETED));
            fixture.presenter.selectThread(fixture.first);
            fixture.await(() -> fixture.selected(fixture.first)
                    && fixture.state().transcript().nextSequence() == 1);
            assertFalse(fixture.state().interaction().busy());
            assertTrue(fixture.state().threads().activeTurn().isEmpty());
            assertEquals(1, fixture.subscriptions.get());
        }
    }

    @Test
    void 导航到另一工作区并返回原会话仍保持真实作用域() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.start();
            fixture.presenter.selectWorkspace(fixture.otherWorkspace);
            fixture.await(() -> fixture.selected(fixture.third)
                    && !fixture.state().interaction().busy());
            assertTrue(fixture.state().interaction().pendingApprovals().isEmpty());
            var navigation = fixture.presenter.navigateToThread(fixture.first.id());
            // 导航提交与订阅建立分属 UI 和后台阶段，必须等待真实订阅完成再验证重复选择幂等。
            fixture.await(() -> navigation.isDone()
                    && fixture.state().threads().activeTurn().isPresent()
                    && fixture.subscriptions.get() == 2);
            assertEquals(fixture.first, navigation.join());
            assertEquals(
                    fixture.first.workspaceId(),
                    fixture.state().threads().selectedWorkspace().orElseThrow().id());
            var repeated = fixture.presenter.navigateToThread(fixture.first.id());
            fixture.await(repeated::isDone);
            assertEquals(1, fixture.server.turnStarts.get());
            assertEquals(2, fixture.subscriptions.get());
        }
    }

    @Test
    void 重新连接从历史复核正在运行的Turn并且不重复发送输入() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.start();
            var replacement = new PresenterRpcServer();
            replacement.streamEnabled = true;
            replacement.requestOverride = fixture::response;
            fixture.connection.set(replacement);
            var reconnect = fixture.presenter.reconnect();
            fixture.await(() -> reconnect.isDone()
                    && fixture.subscriptions.get() == 2
                    && fixture.state().threads().activeTurn().isPresent());
            assertEquals(1, fixture.server.turnStarts.get());
            assertTrue(fixture.state().interaction().busy());
            assertTrue(fixture.server.closed);
        }
    }

    @Test
    void 忙碌时切换角色和重复发送不会再启动Turn() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.start();
            fixture.presenter.selectRole(fixture.server.profile());
            fixture.presenter.send("重复输入");
            fixture.presenter.clearRoleSelection();
            fixture.presenter.send("再次重复输入");
            fixture.drain();
            assertTrue(fixture.state().interaction().busy());
            assertEquals(1, fixture.server.turnStarts.get());
        }
    }

    @Test
    void 运行中的辅助操作失败保留busy和活动Turn而无活动Turn的失败恢复输入() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.start();
            var failed = DesktopStateProjection.failure(fixture.state(), "读取审批失败");
            assertTrue(failed.interaction().busy());
            assertTrue(failed.threads().activeTurn().isPresent());
            var switched = DesktopStateProjection.selectThread(failed, fixture.second);
            assertFalse(DesktopStateProjection.failure(switched, "读取历史失败")
                    .interaction()
                    .busy());
        }
    }

    @Test
    void 旧服务端轮询也按会话归属释放并从历史恢复观察() throws Exception {
        try (Fixture fixture = new Fixture(false)) {
            fixture.presenter.send("旧协议生成");
            fixture.await(() -> fixture.state().threads().activeTurn().isPresent());
            fixture.presenter.selectThread(fixture.second);
            fixture.await(() -> fixture.selected(fixture.second)
                    && !fixture.state().interaction().busy());
            fixture.presenter.selectThread(fixture.first);
            fixture.await(() -> fixture.selected(fixture.first)
                    && fixture.state().threads().activeTurn().isPresent());
            fixture.turns.put(fixture.first.id(), fixture.turn(fixture.first, TurnStatus.CANCELLED));
            fixture.await(() -> !fixture.state().interaction().busy());
            assertTrue(fixture.state().threads().activeTurn().isEmpty());
            assertEquals(1, fixture.server.turnStarts.get());
            assertEquals(0, fixture.subscriptions.get());
        }
    }

    @Test
    void 旧历史恢复失败封锁发送并允许重选同一会话成功重试() throws Exception {
        try (Fixture fixture = new Fixture(false)) {
            var original = fixture.server.requestOverride;
            CountDownLatch entered = new CountDownLatch(1);
            CountDownLatch released = new CountDownLatch(1);
            fixture.server.requestOverride = request -> {
                if (request.method().equals("item/list")) {
                    entered.countDown();
                    awaitLatch(released);
                    throw new IllegalStateException("可控历史读取失败");
                }
                return original.apply(request);
            };
            fixture.presenter.selectThread(fixture.second);
            fixture.drain();
            assertTrue(entered.await(3, TimeUnit.SECONDS));
            released.countDown();
            fixture.await(() -> fixture.state().interaction().error().isPresent());
            assertTrue(fixture.state().interaction().busy());
            fixture.presenter.send("恢复失败时不得发送");
            fixture.drain();
            assertEquals(0, fixture.server.turnStarts.get());
            fixture.server.requestOverride = original;
            fixture.presenter.selectThread(fixture.second);
            fixture.await(() -> !fixture.state().interaction().busy());
            assertTrue(fixture.state().interaction().error().isEmpty());
            fixture.presenter.send("恢复成功后发送");
            fixture.await(() -> fixture.server.turnStarts.get() == 1);
        }
    }

    @Test
    void 旧协议活动观察失败后重选同一会话可补齐已完成Turn并恢复发送() throws Exception {
        try (Fixture fixture = new Fixture(false)) {
            var original = fixture.server.requestOverride;
            fixture.server.requestOverride = request -> {
                if (request.method().equals("item/list")) {
                    throw new IllegalStateException("活动正文追尾失败");
                }
                return original.apply(request);
            };
            fixture.presenter.send("只启动一次");
            fixture.await(() -> fixture.state().interaction().error().isPresent());
            assertTrue(fixture.state().interaction().busy());
            assertTrue(fixture.state().interaction().error().orElseThrow().contains("重新选择"));
            fixture.server.requestOverride = original;
            fixture.turns.put(fixture.first.id(), fixture.turn(fixture.first, TurnStatus.COMPLETED));
            fixture.presenter.selectThread(fixture.first);
            fixture.await(() -> !fixture.state().interaction().busy());
            assertTrue(fixture.state().interaction().error().isEmpty());
            assertTrue(fixture.state().threads().activeTurn().isEmpty());
            assertEquals(1, fixture.state().transcript().nextSequence());
            assertEquals(1, fixture.server.turnStarts.get());
        }
    }

    @Test
    void 跨会话导航读取失败不误解锁原会话且重选原会话能恢复观察() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.start();
            var original = fixture.server.requestOverride;
            fixture.server.requestOverride = request -> {
                if (request.method().equals("thread/read")) {
                    throw new IllegalStateException("导航目标读取失败");
                }
                return original.apply(request);
            };
            var navigation = fixture.presenter.navigateToThread(fixture.second.id());
            fixture.await(navigation::isDone);
            assertTrue(navigation.isCompletedExceptionally());
            assertTrue(fixture.selected(fixture.first));
            assertTrue(fixture.state().interaction().busy());
            assertTrue(fixture.state().interaction().error().isPresent());
            fixture.presenter.send("原会话状态未知时不得发送");
            fixture.drain();
            assertEquals(1, fixture.server.turnStarts.get());
            fixture.server.requestOverride = original;
            fixture.presenter.selectThread(fixture.first);
            fixture.await(() -> fixture.subscriptions.get() == 2);
            assertTrue(fixture.state().threads().activeTurn().isPresent());
            assertTrue(fixture.state().interaction().error().isEmpty());
        }
    }

    private static void awaitLatch(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("RPC 闸门没有按测试约定释放");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError(interrupted);
        }
    }

    private static final class Fixture implements AutoCloseable {
        private final CanonicalJson json = new CanonicalJson();
        private final PresenterRpcServer server = new PresenterRpcServer();
        private final AtomicReference<PresenterRpcServer> connection = new AtomicReference<>(server);
        private final ConversationThread first = server.thread();
        private final ConversationThread second = new ConversationThread(
                ThreadId.random(),
                first.workspaceId(),
                first.parentThreadId(),
                first.executionIntent(),
                "其他会话",
                first.status(),
                first.revision(),
                first.createdAt(),
                first.updatedAt());
        private final Workspace otherWorkspace = new Workspace(
                WorkspaceId.random(),
                "另一工作区",
                server.workspace().root(),
                server.workspace().lifecycle(),
                1,
                first.createdAt(),
                first.updatedAt());
        private final ConversationThread third = new ConversationThread(
                ThreadId.random(),
                otherWorkspace.id(),
                Optional.empty(),
                first.executionIntent(),
                "另一工作区会话",
                first.status(),
                1,
                first.createdAt(),
                first.updatedAt());
        private final Map<ThreadId, AgentTurn> turns = new ConcurrentHashMap<>();
        private final ConcurrentLinkedQueue<Runnable> ui = new ConcurrentLinkedQueue<>();
        private final AtomicReference<DesktopState> latest = new AtomicReference<>();
        private final AtomicInteger subscriptions = new AtomicInteger();
        private final DesktopPresenter presenter = new DesktopPresenter(
                notifications -> connection.get().client(notifications), ui::add, Clock.systemUTC());
        private volatile Runnable beforeStartReply = () -> {};

        private Fixture() throws Exception {
            this(true);
        }

        private Fixture(boolean streaming) throws Exception {
            server.streamEnabled = streaming;
            server.requestOverride = this::response;
            presenter.subscribe(latest::set);
            presenter.connect();
            await(() -> selected(first)
                    && server.historyReads.get() == 1
                    && !state().interaction().busy());
            drain();
        }

        private Optional<Object> response(JsonRpcRequest request) {
            return switch (request.method()) {
                case "workspace/list" ->
                    Optional.of(new CoreRpcContracts.WorkspaceListResult(List.of(server.workspace(), otherWorkspace)));
                case "thread/list" ->
                    Optional.of(new CoreRpcContracts.ThreadListResult(
                            json.decode(request.params(), CoreRpcContracts.WorkspaceQuery.class)
                                            .workspaceId()
                                            .equals(otherWorkspace.id())
                                    ? List.of(third)
                                    : List.of(first, second)));
                case "thread/read" ->
                    Optional.of(thread(json.decode(request.params(), CoreRpcContracts.ThreadQuery.class)
                            .threadId()));
                case "item/history" -> Optional.of(history(request));
                case "item/list" -> Optional.of(items(request));
                case "turn/start" -> Optional.of(started(request));
                case "turn/read" ->
                    Optional.of(turns.values().stream()
                            .filter(turn -> turn.id()
                                    .equals(json.decode(request.params(), CoreRpcContracts.TurnQuery.class)
                                            .turnId()))
                            .findFirst()
                            .orElseThrow());
                case "turn/stream/subscribe" -> {
                    subscriptions.incrementAndGet();
                    yield Optional.empty();
                }
                default -> Optional.empty();
            };
        }

        private ItemHistoryResult history(JsonRpcRequest request) {
            server.historyReads.incrementAndGet();
            var query = json.decode(request.params(), TurnStreamRpcContracts.ItemHistoryRequest.class);
            AgentTurn turn = turns.get(query.threadId());
            if (turn == null) {
                return new ItemHistoryResult(List.of(), 0, false);
            }
            var entry = new ItemHistoryEntry(
                    ItemId.random(),
                    turn.id(),
                    1,
                    "message",
                    Optional.of(MessageRole.USER),
                    "已发送的输入",
                    Optional.empty(),
                    false,
                    turn.createdAt(),
                    List.of(),
                    List.of());
            return new ItemHistoryResult(List.of(entry), 1, false);
        }

        private CoreRpcContracts.ItemListResult items(JsonRpcRequest request) {
            server.historyReads.incrementAndGet();
            var query = json.decode(request.params(), CoreRpcContracts.ItemList.class);
            AgentTurn turn = turns.get(query.threadId());
            if (turn == null || query.afterSequence() >= 1) {
                return new CoreRpcContracts.ItemListResult(List.of(), query.afterSequence());
            }
            var message = new CorePayloads.Message(MessageRole.USER, "已发送的输入", List.of(), Optional.empty());
            var item = new ItemEnvelope(
                    ItemId.random(),
                    turn.id(),
                    1,
                    "message",
                    CoreSchemas.MESSAGE,
                    "core",
                    ItemStatus.COMPLETED,
                    json.encode(message),
                    turn.createdAt(),
                    Optional.of(turn.createdAt()));
            return new CoreRpcContracts.ItemListResult(List.of(item), 1);
        }

        private Object started(JsonRpcRequest request) {
            var command = json.decode(request.params(), WriteCommand.class);
            var payload = json.decode(command.payload(), CoreRpcContracts.TurnStartPayload.class);
            AgentTurn started = turn(thread(payload.threadId()), TurnStatus.RUNNING);
            turns.put(payload.threadId(), started);
            server.turnStarts.incrementAndGet();
            beforeStartReply.run();
            return new CoreRpcContracts.TurnStartResult(started, started.resolvedConfig());
        }

        private AgentTurn turn(ConversationThread thread, TurnStatus status) {
            AgentTurn value = DesktopTestFixtures.turn(thread, status, 1);
            TurnId id =
                    thread.id().equals(first.id()) ? value.id() : TurnId.parse("8569e4b5-6f46-4439-bd44-a10fe1b363c0");
            return new AgentTurn(
                    id,
                    value.threadId(),
                    value.status(),
                    value.revision(),
                    value.budget(),
                    value.role(),
                    value.provider(),
                    value.permissionProfile(),
                    value.executionRoot(),
                    value.promptManifestDigest(),
                    value.toolCatalogDigest(),
                    value.errorCode(),
                    value.createdAt(),
                    value.updatedAt(),
                    value.resolvedConfig());
        }

        private ConversationThread thread(ThreadId id) {
            return id.equals(first.id()) ? first : id.equals(second.id()) ? second : third;
        }

        private void start() throws Exception {
            presenter.send("开始生成");
            await(() ->
                    subscriptions.get() == 1 && state().threads().activeTurn().isPresent());
        }

        private boolean selected(ConversationThread thread) {
            return latest.get() != null
                    && state().threads()
                            .selectedThread()
                            .map(value -> value.id().equals(thread.id()))
                            .orElse(false);
        }

        private DesktopState state() {
            return latest.get();
        }

        private void drain() {
            Runnable next;
            while ((next = ui.poll()) != null) {
                next.run();
            }
        }

        private void await(BooleanSupplier condition) throws Exception {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (System.nanoTime() < deadline) {
                drain();
                if (condition.getAsBoolean()) {
                    return;
                }
                Thread.sleep(10);
            }
            throw new AssertionError("导航状态没有在截止前达到预期");
        }

        @Override
        public void close() throws Exception {
            presenter.close();
        }
    }
}
