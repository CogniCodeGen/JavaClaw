package com.javaclaw.desktop;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.Test;

import com.javaclaw.api.ItemHistoryEntry;
import com.javaclaw.api.ItemHistoryResult;
import com.javaclaw.api.ItemId;
import com.javaclaw.api.MessageRole;
import com.javaclaw.client.sdk.JavaClawClient;
import com.javaclaw.desktop.state.ConnectionState;
import com.javaclaw.desktop.state.TranscriptState;
import com.javaclaw.protocol.CanonicalJson;
import com.javaclaw.protocol.TurnStreamRpcContracts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 只延迟 UI 提交以确定性复现同一连接上的响应乱序，不启动 JavaFX 或真实模型。 */
class DesktopHistoryNavigationTest {
    @Test
    void 回到最新后迟到的更早页面不能撤销跟随或覆盖窗口() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.owner.earlier();
            fixture.next().run();
            Runnable delayed = fixture.next();
            fixture.owner.following(true);
            fixture.await(() -> fixture.transcript().nextSequence() == 200);
            delayed.run();
            assertTrue(fixture.transcript().following());
            assertEquals(List.of(200L), fixture.sequences());
        }
    }

    @Test
    void 新的翻页意图使旧的回到最新响应失效() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.owner.following(true);
            fixture.next().run();
            fixture.next().run();
            Runnable delayed = fixture.next();
            fixture.owner.earlier();
            fixture.await(() -> fixture.sequences().contains(50L));
            delayed.run();
            assertFalse(fixture.transcript().following());
            assertEquals(List.of(50L, 100L), fixture.sequences());
        }
    }

    @Test
    void 已被回到最新替代的翻页错误不重新显示失败状态() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.failEarlier = true;
            fixture.owner.earlier();
            fixture.next().run();
            Runnable delayed = fixture.next();
            fixture.owner.following(true);
            fixture.await(() -> fixture.transcript().nextSequence() == 200);
            delayed.run();
            assertTrue(fixture.store.state().interaction().error().isEmpty());
            assertTrue(fixture.transcript().following());
        }
    }

    @Test
    void 重复暂停跟随通知不使正在加载的更早页面失效() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.owner.earlier();
            fixture.next().run();
            Runnable delayed = fixture.next();
            fixture.owner.following(false);
            fixture.next().run();
            delayed.run();
            assertEquals(List.of(50L, 100L), fixture.sequences());
            assertFalse(fixture.transcript().following());
        }
    }

    @Test
    void 连续回到最新再暂停时二次排队的旧更新不能覆盖最后意图() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.owner.following(true);
            fixture.owner.following(false);
            fixture.next().run();
            fixture.next().run();
            fixture.workers.shutdown();
            assertTrue(fixture.workers.awaitTermination(5, TimeUnit.SECONDS));
            Runnable action;
            while ((action = fixture.ui.poll()) != null) {
                action.run();
            }
            assertFalse(fixture.transcript().following());
            assertEquals(100, fixture.transcript().nextSequence());
        }
    }

    private static final class Fixture implements AutoCloseable {
        private final PresenterRpcServer server = new PresenterRpcServer();
        private final CanonicalJson json = new CanonicalJson();
        private final DesktopStore store = new DesktopStore();
        private final ConcurrentLinkedQueue<Runnable> ui = new ConcurrentLinkedQueue<>();
        private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
        private final JavaClawClient client;
        private final DesktopTurnStreamCoordinator streams;
        private final DesktopConversationCoordinator owner;
        private volatile boolean failEarlier;

        private Fixture() throws Exception {
            server.streamEnabled = true;
            server.requestOverride = request -> {
                if (!request.method().equals("item/history")) {
                    return Optional.empty();
                }
                var query = json.decode(request.params(), TurnStreamRpcContracts.ItemHistoryRequest.class);
                if (query.beforeSequence() > 0 && failEarlier) {
                    throw new IllegalStateException("旧翻页失败");
                }
                return Optional.of(query.beforeSequence() > 0 ? page(50, 100, false) : page(200, 200, true));
            };
            client = server.client(ignored -> {});
            streams = new DesktopTurnStreamCoordinator(store, ui::add, workers);
            owner = new DesktopConversationCoordinator(store, ui::add, workers, streams, () -> client, ignored -> {});
            store.update(state ->
                    DesktopStateProjection.connection(state, ConnectionState.connected("本地测试", Instant.EPOCH)));
            store.update(state -> DesktopStateProjection.catalog(
                    state, List.of(server.workspace()), server.workspace(), List.of(server.thread())));
            store.update(state -> DesktopStateProjection.selectThread(state, server.thread()));
            store.update(state -> DesktopStateProjection.transcript(
                    state,
                    new TranscriptState(
                            List.of(),
                            100,
                            false,
                            Optional.empty(),
                            true,
                            page(100, 100, true).items())));
        }

        private ItemHistoryResult page(long sequence, long latest, boolean hasEarlier) {
            var entry = new ItemHistoryEntry(
                    ItemId.random(),
                    DesktopTestFixtures.turn().id(),
                    sequence,
                    "message",
                    Optional.of(MessageRole.ASSISTANT),
                    "消息 " + sequence,
                    Optional.empty(),
                    false,
                    Instant.EPOCH,
                    List.of(),
                    List.of());
            return new ItemHistoryResult(List.of(entry), latest, hasEarlier);
        }

        private TranscriptState transcript() {
            return store.state().transcript();
        }

        private List<Long> sequences() {
            return transcript().history().stream()
                    .map(ItemHistoryEntry::sequence)
                    .toList();
        }

        private Runnable next() throws InterruptedException {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (System.nanoTime() < deadline) {
                Runnable action = ui.poll();
                if (action != null) {
                    return action;
                }
                Thread.sleep(5);
            }
            throw new AssertionError("本地 SDK 未提交预期 UI 回调");
        }

        private void await(BooleanSupplier condition) throws InterruptedException {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (System.nanoTime() < deadline) {
                Runnable action;
                while ((action = ui.poll()) != null) {
                    action.run();
                }
                if (condition.getAsBoolean()) {
                    return;
                }
                Thread.sleep(5);
            }
            assertTrue(condition.getAsBoolean(), "阅读导航未达到预期状态");
        }

        @Override
        public void close() throws Exception {
            owner.close();
            streams.close();
            workers.shutdownNow();
            client.close();
        }
    }
}
