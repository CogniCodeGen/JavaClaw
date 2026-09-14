package com.javaclaw.desktop;

import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.Test;

import com.javaclaw.api.AgentTurn;
import com.javaclaw.api.ConversationThread;
import com.javaclaw.api.ExecutionOverrides;
import com.javaclaw.api.ItemHistoryEntry;
import com.javaclaw.api.ItemHistoryResult;
import com.javaclaw.api.ItemId;
import com.javaclaw.api.MessageRole;
import com.javaclaw.api.ThreadId;
import com.javaclaw.api.TurnStatus;
import com.javaclaw.desktop.state.DesktopState;
import com.javaclaw.desktop.state.OutgoingMessage;
import com.javaclaw.protocol.CanonicalJson;
import com.javaclaw.protocol.CoreRpcContracts;
import com.javaclaw.protocol.JsonRpcRequest;
import com.javaclaw.protocol.TurnStreamRpcContracts;
import com.javaclaw.protocol.WriteCommand;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 真实 SDK 请求在服务端已提交后丢失回执，UI 队列先完成导航，再接收迟到异常。 */
class DesktopLateSendNavigationTest {
    @Test
    void 切换后的迟到失败只保留原会话记录且返回后原键重试恢复同一Turn() throws Exception {
        try (Fixture fixture = new Fixture()) {
            var sent = fixture.presenter.send("原会话的完整问题", ExecutionOverrides.empty());
            fixture.drain();
            assertTrue(fixture.entered.await(3, TimeUnit.SECONDS));
            String id = fixture.state().transcript().outgoing().orElseThrow().id();
            fixture.presenter.selectThread(fixture.second);
            // 内存 transport 在 SDK 的发送锁内执行闸门；先验证本地导航，释放后才能完成第二会话的历史 RPC。
            fixture.await(() -> fixture.selected(fixture.second));
            assertTrue(fixture.state().transcript().outgoings().isEmpty());
            var before = fixture.state();
            fixture.release.countDown();
            fixture.await(() -> sent.isDone() && !fixture.state().interaction().busy());
            assertThrows(CompletionException.class, sent::join);
            assertTrue(fixture.selected(fixture.second));
            assertEquals(before.transcript(), fixture.state().transcript());
            assertEquals(
                    before.interaction().error(), fixture.state().interaction().error());
            assertFalse(fixture.state().interaction().busy());
            assertTrue(fixture.state().threads().activeTurn().isEmpty());
            var wrongScope = fixture.presenter.retrySend(id);
            fixture.await(wrongScope::isDone);
            assertThrows(CompletionException.class, wrongScope::join);
            assertEquals(1, fixture.commands.size());
            fixture.presenter.selectThread(fixture.first);
            fixture.await(() -> fixture.selected(fixture.first)
                    && !fixture.state().interaction().busy());
            OutgoingMessage retained = fixture.state().transcript().outgoing().orElseThrow();
            assertEquals(id, retained.id());
            assertEquals("原会话的完整问题", retained.text());
            assertEquals(OutgoingMessage.Status.UNCONFIRMED, retained.status());
            var retry = fixture.presenter.retrySend(id);
            fixture.await(() -> retry.isDone() && !fixture.state().interaction().busy());
            assertEquals(fixture.accepted.get().id(), retry.join().id());
            assertEquals(2, fixture.commands.size());
            assertEquals(fixture.commands.getFirst(), fixture.commands.getLast());
            assertEquals(1, fixture.creations.get());
            assertTrue(fixture.state().transcript().outgoings().isEmpty());
            assertEquals(1, fixture.state().transcript().history().size());
        }
    }

    private static final class Fixture implements AutoCloseable {
        private final PresenterRpcServer server = new PresenterRpcServer();
        private final CanonicalJson json = new CanonicalJson();
        private final ConversationThread first = server.thread();
        private final ConversationThread second = new ConversationThread(
                ThreadId.random(),
                first.workspaceId(),
                Optional.empty(),
                first.executionIntent(),
                "另一会话",
                first.status(),
                first.revision(),
                first.createdAt(),
                first.updatedAt());
        private final ConcurrentLinkedQueue<Runnable> ui = new ConcurrentLinkedQueue<>();
        private final AtomicReference<DesktopState> state = new AtomicReference<>();
        private final AtomicReference<AgentTurn> accepted = new AtomicReference<>();
        private final List<WriteCommand> commands = new CopyOnWriteArrayList<>();
        private final AtomicInteger creations = new AtomicInteger();
        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private final DesktopPresenter presenter = new DesktopPresenter(server::client, ui::add, Clock.systemUTC());

        private Fixture() throws Exception {
            server.streamEnabled = true;
            server.requestOverride = this::response;
            presenter.subscribe(state::set);
            presenter.connect();
            await(() -> selected(first) && !state().interaction().busy());
        }

        private Optional<Object> response(JsonRpcRequest request) {
            return switch (request.method()) {
                case "thread/list" -> Optional.of(new CoreRpcContracts.ThreadListResult(List.of(first, second)));
                case "item/history" -> Optional.of(history(request));
                case "turn/start" -> Optional.of(start(request));
                case "turn/read" -> Optional.of(accepted.get());
                default -> Optional.empty();
            };
        }

        private ItemHistoryResult history(JsonRpcRequest request) {
            var query = json.decode(request.params(), TurnStreamRpcContracts.ItemHistoryRequest.class);
            AgentTurn turn = accepted.get();
            if (!query.threadId().equals(first.id()) || turn == null) {
                return new ItemHistoryResult(List.of(), 0, false);
            }
            var entry = new ItemHistoryEntry(
                    ItemId.random(),
                    turn.id(),
                    1,
                    "message",
                    Optional.of(MessageRole.USER),
                    "原会话的完整问题",
                    Optional.empty(),
                    false,
                    turn.createdAt(),
                    List.of(),
                    List.of());
            return new ItemHistoryResult(List.of(entry), 1, false);
        }

        private CoreRpcContracts.TurnStartResult start(JsonRpcRequest request) {
            commands.add(json.decode(request.params(), WriteCommand.class));
            if (accepted.get() == null) {
                accepted.set(DesktopTestFixtures.turn(first, TurnStatus.COMPLETED, 2));
                creations.incrementAndGet();
                entered.countDown();
                try {
                    if (!release.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("测试未能在导航完成后释放启动回执");
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(interrupted);
                }
                throw new IllegalStateException("服务端已提交，发送回执丢失");
            }
            AgentTurn turn = accepted.get();
            return new CoreRpcContracts.TurnStartResult(turn, turn.resolvedConfig());
        }

        private DesktopState state() {
            return state.get();
        }

        private boolean selected(ConversationThread thread) {
            return state.get() != null
                    && state().threads()
                            .selectedThread()
                            .filter(value -> value.id().equals(thread.id()))
                            .isPresent();
        }

        private void await(BooleanSupplier condition) throws Exception {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (System.nanoTime() < deadline) {
                drain();
                if (condition.getAsBoolean()) {
                    return;
                }
                Thread.sleep(5);
            }
            throw new AssertionError("迟到发送导航测试未达到预期状态");
        }

        private void drain() {
            Runnable action;
            while ((action = ui.poll()) != null) {
                action.run();
            }
        }

        @Override
        public void close() throws Exception {
            release.countDown();
            presenter.close();
        }
    }
}
