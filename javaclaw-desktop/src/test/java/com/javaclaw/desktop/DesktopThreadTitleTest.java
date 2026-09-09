package com.javaclaw.desktop;

import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.Test;

import com.javaclaw.api.ConversationThread;
import com.javaclaw.api.ExecutionOverrides;
import com.javaclaw.api.ThreadId;
import com.javaclaw.api.TurnStatus;
import com.javaclaw.desktop.state.ConnectionState;
import com.javaclaw.desktop.state.DesktopState;
import com.javaclaw.desktop.state.OutgoingMessage;
import com.javaclaw.protocol.CanonicalJson;
import com.javaclaw.protocol.CoreRpcContracts;
import com.javaclaw.protocol.JsonRpcRequest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DesktopThreadTitleTest {
    @Test
    void 标题读取阻塞不延迟发送确认且更新标题保留正文与运行状态() throws Exception {
        try (Fixture fixture = new Fixture("新对话", false)) {
            fixture.connect();
            var sent = fixture.presenter.send("分析聊天性能", ExecutionOverrides.empty());
            fixture.await(() -> sent.isDone() && fixture.reads.get() == 1);
            assertEquals(fixture.first.id(), sent.get().threadId());
            assertEquals(
                    OutgoingMessage.Status.ACCEPTED,
                    fixture.state().transcript().outgoing().orElseThrow().status());
            assertTrue(fixture.state().interaction().busy());
            assertTrue(fixture.state().threads().activeTurn().isPresent());
            assertEquals("新对话", fixture.selected().title());
            fixture.release.countDown();
            fixture.await(() -> fixture.selected().title().equals("分析聊天性能"));
            assertEquals(
                    "分析聊天性能", fixture.state().threads().threads().getFirst().title());
            assertTrue(fixture.state().threads().activeTurn().isPresent());
            assertTrue(fixture.state().interaction().busy());
            assertFalse(sent.isCompletedExceptionally());
        }
    }

    @Test
    void 辅助标题查询失败不把已接受消息标成发送失败() throws Exception {
        try (Fixture fixture = new Fixture("新对话", true)) {
            fixture.connect();
            var sent = fixture.presenter.send("分析聊天性能", ExecutionOverrides.empty());
            fixture.await(() -> sent.isDone() && fixture.reads.get() == 1);
            fixture.release.countDown();
            fixture.await(() -> fixture.server.turnReads.get() > 0);
            assertFalse(sent.isCompletedExceptionally());
            assertTrue(fixture.state().interaction().error().isEmpty());
            assertEquals(
                    OutgoingMessage.Status.ACCEPTED,
                    fixture.state().transcript().outgoing().orElseThrow().status());
            assertEquals("新对话", fixture.selected().title());
        }
    }

    @Test
    void 切换后迟到标题不污染新会话并在返回时补读保存的标题() throws Exception {
        try (Fixture fixture = new Fixture("新对话", false)) {
            fixture.connect();
            var sent = fixture.presenter.send("分析聊天性能", ExecutionOverrides.empty());
            fixture.await(() -> sent.isDone() && fixture.reads.get() == 1);
            fixture.presenter.selectThread(fixture.second);
            fixture.await(() -> fixture.selected().id().equals(fixture.second.id()));
            fixture.release.countDown();
            fixture.await(() -> !fixture.state().interaction().busy());
            assertEquals("另一个任务", fixture.selected().title());
            assertTrue(fixture.state().transcript().outgoing().isEmpty());
            fixture.presenter.selectThread(fixture.first);
            fixture.await(() -> fixture.selected().title().equals("分析聊天性能"));
            assertEquals(2, fixture.reads.get());
            assertEquals(1, fixture.server.turnStarts.get());
        }
    }

    @Test
    void 已有自定义标题不额外查询且旧版本元数据不覆盖新标题() throws Exception {
        try (Fixture fixture = new Fixture("自定义任务", false)) {
            fixture.connect();
            var sent = fixture.presenter.send("分析聊天性能", ExecutionOverrides.empty());
            fixture.await(sent::isDone);
            assertEquals(0, fixture.reads.get());
            DesktopState before = fixture.state();
            ConversationThread latest = renamed(fixture.first, "当前保存的标题", 5);
            DesktopState updated = DesktopStateProjection.threadMetadata(before, latest);
            assertSame(before.transcript(), updated.transcript());
            assertSame(before.interaction(), updated.interaction());
            assertEquals(before.threads().activeTurn(), updated.threads().activeTurn());
            DesktopState stale = DesktopStateProjection.threadMetadata(updated, renamed(fixture.first, "迟到标题", 4));
            assertEquals(
                    "当前保存的标题", stale.threads().selectedThread().orElseThrow().title());
            assertEquals("当前保存的标题", stale.threads().threads().getFirst().title());
        }
    }

    private static ConversationThread renamed(ConversationThread source, String title, long revision) {
        return new ConversationThread(
                source.id(),
                source.workspaceId(),
                source.parentThreadId(),
                source.executionIntent(),
                title,
                source.status(),
                revision,
                source.createdAt(),
                source.updatedAt().plusSeconds(revision));
    }

    /** UI 提交串行排队；只阻塞辅助标题查询，便于验证发送确认和跨会话隔离。 */
    private static final class Fixture implements AutoCloseable {
        private final PresenterRpcServer server = new PresenterRpcServer();
        private final ConcurrentLinkedQueue<Runnable> ui = new ConcurrentLinkedQueue<>();
        private final DesktopPresenter presenter = new DesktopPresenter(server::client, ui::add, Clock.systemUTC());
        private final CanonicalJson json = new CanonicalJson();
        private final CountDownLatch release = new CountDownLatch(1);
        private final AtomicInteger reads = new AtomicInteger();
        private final ConversationThread first;
        private final ConversationThread second;
        private final boolean fail;
        private DesktopState state = DesktopState.initial();

        private Fixture(String title, boolean fail) {
            this.fail = fail;
            first = renamed(server.thread(), title, 1);
            second = new ConversationThread(
                    ThreadId.random(),
                    first.workspaceId(),
                    first.parentThreadId(),
                    first.executionIntent(),
                    "另一个任务",
                    first.status(),
                    1,
                    first.createdAt(),
                    first.updatedAt());
            server.completion = TurnStatus.RUNNING;
            server.requestOverride = this::reply;
            presenter.subscribe(value -> state = value);
        }

        private Optional<Object> reply(JsonRpcRequest request) {
            return switch (request.method()) {
                case "thread/list" -> Optional.of(new CoreRpcContracts.ThreadListResult(List.of(first, second)));
                case "thread/read" -> Optional.of(readTitle());
                case "item/list" -> Optional.of(items(request));
                default -> Optional.empty();
            };
        }

        private ConversationThread readTitle() {
            reads.incrementAndGet();
            try {
                if (!release.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("标题读取闸门未释放");
                }
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(failure);
            }
            if (fail) {
                throw new IllegalStateException("标题查询暂时不可用");
            }
            return renamed(first, "分析聊天性能", 3);
        }

        private CoreRpcContracts.ItemListResult items(JsonRpcRequest request) {
            var query = json.decode(request.params(), CoreRpcContracts.ItemList.class);
            if (server.turnStarts.get() > 0 && query.threadId().equals(first.id()) && query.afterSequence() == 0) {
                return new CoreRpcContracts.ItemListResult(List.of(DesktopTestFixtures.item(1)), 1);
            }
            return new CoreRpcContracts.ItemListResult(List.of(), query.afterSequence());
        }

        private void connect() throws Exception {
            presenter.connect();
            await(() -> state.connection().status() == ConnectionState.Status.CONNECTED
                    && state.threads().selectedThread().isPresent()
                    && !state.interaction().busy());
            assertEquals(0, reads.get(), "空对话无需读取自动标题");
        }

        private DesktopState state() {
            return state;
        }

        private ConversationThread selected() {
            return state.threads().selectedThread().orElseThrow();
        }

        private void await(BooleanSupplier condition) throws Exception {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            do {
                for (Runnable action; (action = ui.poll()) != null; ) {
                    action.run();
                }
                if (condition.getAsBoolean()) {
                    return;
                }
                Thread.sleep(5);
            } while (System.nanoTime() < deadline);
            assertTrue(condition.getAsBoolean(), "标题状态未在期限内到达");
        }

        @Override
        public void close() throws Exception {
            release.countDown();
            presenter.close();
        }
    }
}
