package com.javaclaw.desktop;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import com.javaclaw.api.AgentTurn;
import com.javaclaw.api.ConversationThread;
import com.javaclaw.api.ThreadId;
import com.javaclaw.api.TurnId;
import com.javaclaw.api.TurnStatus;
import com.javaclaw.client.sdk.JavaClawClient;
import com.javaclaw.desktop.state.ConnectionState;
import com.javaclaw.protocol.CanonicalJson;
import com.javaclaw.protocol.CoreRpcContracts;
import com.javaclaw.protocol.WriteCommand;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 真实 SDK 的取消读取回执被闸门延迟，导航先在串行 UI 队列中生效。 */
class DesktopCancellationNavigationTest {
    @Test
    void 停止后立即切换会话仍只取消原Turn且不污染新页面() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.owner.cancel();
            fixture.drain();
            assertTrue(fixture.readEntered.await(5, TimeUnit.SECONDS));
            fixture.owner.selectThread(fixture.second);
            fixture.drain();
            assertEquals(
                    fixture.second.id(),
                    fixture.store
                            .state()
                            .threads()
                            .selectedThread()
                            .orElseThrow()
                            .id());
            fixture.releaseRead.countDown();
            fixture.finish();
            assertEquals(List.of(fixture.original.id()), List.copyOf(fixture.cancelled));
            assertEquals(
                    fixture.second.id(),
                    fixture.store
                            .state()
                            .threads()
                            .selectedThread()
                            .orElseThrow()
                            .id());
            assertTrue(fixture.store.state().threads().activeTurn().isEmpty());
            assertFalse(fixture.store.state().interaction().busy());
            assertTrue(fixture.store.state().interaction().error().isEmpty());
        }
    }

    @Test
    void 只切换会话不会查询或取消原Turn() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.owner.selectThread(fixture.second);
            fixture.drain();
            fixture.finish();
            assertEquals(1, fixture.readEntered.getCount());
            assertTrue(fixture.cancelled.isEmpty());
        }
    }

    @Test
    void 取消读取期间连接关闭后不再提交写入() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.owner.cancel();
            fixture.drain();
            assertTrue(fixture.readEntered.await(5, TimeUnit.SECONDS));
            fixture.owner.close();
            fixture.releaseRead.countDown();
            fixture.finish();
            assertTrue(fixture.cancelled.isEmpty());
        }
    }

    private static final class Fixture implements AutoCloseable {
        private final PresenterRpcServer server = new PresenterRpcServer();
        private final CanonicalJson json = new CanonicalJson();
        private final DesktopStore store = new DesktopStore();
        private final ConcurrentLinkedQueue<Runnable> ui = new ConcurrentLinkedQueue<>();
        private final ConcurrentLinkedQueue<TurnId> cancelled = new ConcurrentLinkedQueue<>();
        private final CountDownLatch readEntered = new CountDownLatch(1);
        private final CountDownLatch releaseRead = new CountDownLatch(1);
        private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
        private final AgentTurn original = DesktopTestFixtures.turn();
        private final ConversationThread second = new ConversationThread(
                ThreadId.random(),
                server.workspace().id(),
                Optional.empty(),
                server.thread().executionIntent(),
                "另一会话",
                server.thread().status(),
                1,
                Instant.EPOCH,
                Instant.EPOCH);
        private final JavaClawClient client;
        private final DesktopTurnStreamCoordinator streams;
        private final DesktopConversationCoordinator owner;

        private Fixture() throws Exception {
            server.requestOverride = request -> {
                if (request.method().equals("turn/read")) {
                    assertEquals(
                            original.id(),
                            json.decode(request.params(), CoreRpcContracts.TurnQuery.class)
                                    .turnId());
                    readEntered.countDown();
                    awaitRelease();
                    return Optional.of(original);
                }
                if (request.method().equals("turn/cancel")) {
                    WriteCommand command = json.decode(request.params(), WriteCommand.class);
                    var payload = json.decode(command.payload(), CoreRpcContracts.TurnCancelPayload.class);
                    assertEquals(original.revision(), command.expectedRevision());
                    cancelled.add(payload.turnId());
                    return Optional.of(DesktopTestFixtures.turn(server.thread(), TurnStatus.CANCELLED, 2));
                }
                if (request.method().equals("item/list")) {
                    var query = json.decode(request.params(), CoreRpcContracts.ItemList.class);
                    return Optional.of(new CoreRpcContracts.ItemListResult(List.of(), query.afterSequence()));
                }
                return Optional.empty();
            };
            client = server.client(ignored -> {});
            streams = new DesktopTurnStreamCoordinator(store, ui::add, workers);
            owner = new DesktopConversationCoordinator(store, ui::add, workers, streams, () -> client, ignored -> {});
            store.update(state ->
                    DesktopStateProjection.connection(state, ConnectionState.connected("本地测试", Instant.EPOCH)));
            store.update(state -> DesktopStateProjection.catalog(
                    state, List.of(server.workspace()), server.workspace(), List.of(server.thread(), second)));
            store.update(state -> DesktopStateProjection.selectThread(state, server.thread()));
            store.update(state -> DesktopStateProjection.activeTurn(state, original));
        }

        private void awaitRelease() {
            try {
                assertTrue(releaseRead.await(5, TimeUnit.SECONDS), "测试未释放取消读取");
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                throw new AssertionError(failure);
            }
        }

        private void drain() {
            Runnable action;
            while ((action = ui.poll()) != null) {
                action.run();
            }
        }

        private void finish() throws InterruptedException {
            workers.shutdown();
            assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS));
            drain();
        }

        @Override
        public void close() throws Exception {
            releaseRead.countDown();
            owner.close();
            streams.close();
            workers.shutdownNow();
            client.close();
        }
    }
}
