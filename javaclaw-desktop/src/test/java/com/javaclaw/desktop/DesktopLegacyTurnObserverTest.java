package com.javaclaw.desktop;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongFunction;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;

import com.javaclaw.api.CanonicalPayload;
import com.javaclaw.api.ItemEnvelope;
import com.javaclaw.api.TurnStatus;
import com.javaclaw.client.sdk.JavaClawClient;
import com.javaclaw.protocol.CanonicalJson;
import com.javaclaw.protocol.CoreRpcContracts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 本地 SDK 夹具控制终态提交和分页顺序；不启动 JavaFX，不访问真实模型。 */
class DesktopLegacyTurnObserverTest {
    @Test
    void 终态仍有一百零一条消息时读完第二页才清除活动状态() throws Exception {
        try (Fixture fixture = new Fixture(101)) {
            var pages = fixture.pages;
            fixture.pages = cursor -> {
                assertTrue(fixture.store.state().threads().activeTurn().isPresent());
                assertTrue(fixture.store.state().interaction().busy());
                assertTrue(fixture.ui.isEmpty(), "终态所有页面齐备前不能排队清除活动状态");
                return pages.apply(cursor);
            };
            fixture.observe();
            assertEquals(1, fixture.ui.size());
            fixture.drain();
            assertEquals(List.of(0L, 100L), fixture.cursors);
            assertEquals(101, fixture.store.state().transcript().nextSequence());
            assertEquals(101, fixture.store.state().transcript().items().size());
            assertFalse(fixture.store.state().interaction().busy());
            assertTrue(fixture.store.state().threads().activeTurn().isEmpty());
        }
    }

    @Test
    void 最后消息在正文查询后提交时下一次终态追尾仍会补齐() throws Exception {
        try (Fixture fixture = new Fixture(1)) {
            fixture.finished.set(false);
            var pages = fixture.pages;
            fixture.pages = cursor -> {
                if (!fixture.finished.getAndSet(true)) {
                    return new CoreRpcContracts.ItemListResult(List.of(), cursor);
                }
                return pages.apply(cursor);
            };
            fixture.observe();
            fixture.drain();
            assertEquals(1, fixture.store.state().transcript().nextSequence());
            assertEquals(1, fixture.store.state().transcript().items().size());
            assertEquals(List.of("turn", "items", "turn", "items"), fixture.order);
        }
    }

    @Test
    void 终态恰好两百条时继续读取确认空页而不遗漏整页边界() throws Exception {
        try (Fixture fixture = new Fixture(200)) {
            fixture.observe();
            fixture.drain();
            assertEquals(List.of(0L, 100L, 200L), fixture.cursors);
            assertEquals(200, fixture.store.state().transcript().items().size());
            assertFalse(fixture.store.state().interaction().busy());
        }
    }

    @Test
    void 已经收到所有正文时终态空尾页保留原消息并恢复发送() throws Exception {
        try (Fixture fixture = new Fixture(1)) {
            fixture.store.update(state -> DesktopStateProjection.transcript(
                    state, new com.javaclaw.desktop.state.TranscriptState(fixture.entries, 1, true)));
            fixture.observe();
            fixture.drain();
            assertEquals(List.of(1L), fixture.cursors);
            assertEquals(fixture.entries, fixture.store.state().transcript().items());
            assertFalse(fixture.store.state().interaction().busy());
        }
    }

    @Test
    void 终态追尾只保留最近五百条且一次提交UI() throws Exception {
        try (Fixture fixture = new Fixture(750)) {
            fixture.observe();
            assertEquals(1, fixture.ui.size());
            fixture.drain();
            var transcript = fixture.store.state().transcript();
            assertEquals(750, transcript.nextSequence());
            assertEquals(500, transcript.items().size());
            assertEquals(251, transcript.items().getFirst().sequence());
        }
    }

    @Test
    void 终态追尾正文缓存仍受八MiB预算约束并保留最后消息() throws Exception {
        try (Fixture fixture = new Fixture(300)) {
            fixture.entries = fixture.entries.stream()
                    .map(DesktopLegacyTurnObserverTest::large)
                    .toList();
            fixture.observe();
            fixture.drain();
            var transcript = fixture.store.state().transcript();
            long bytes = transcript.items().stream()
                    .mapToLong(item -> item.payload().json().length() * 2L + 1024)
                    .sum();
            assertTrue(bytes <= 8L * 1024 * 1024);
            assertTrue(transcript.items().size() < 300);
            assertEquals(300, transcript.items().getLast().sequence());
        }
    }

    @Test
    void 终态追尾期间切换会话立即停止且不提交旧页或终态() throws Exception {
        try (Fixture fixture = new Fixture(200)) {
            var pages = fixture.pages;
            fixture.pages = cursor -> {
                var result = pages.apply(cursor);
                fixture.cancelled.set(true);
                fixture.store.update(state -> DesktopStateProjection.selectThread(state, fixture.server.thread()));
                return result;
            };
            assertThrows(CancellationException.class, fixture::observe);
            assertEquals(List.of(0L), fixture.cursors);
            assertTrue(fixture.ui.isEmpty());
            assertEquals(0, fixture.store.state().transcript().nextSequence());
        }
    }

    @Test
    void 终态坏页明确失败并保留活动状态供重选恢复() throws Exception {
        try (Fixture fixture = new Fixture(1)) {
            fixture.pages = cursor -> new CoreRpcContracts.ItemListResult(fixture.entries, cursor);
            assertThrows(IllegalStateException.class, fixture::observe);
            assertTrue(fixture.ui.isEmpty());
            assertTrue(fixture.store.state().threads().activeTurn().isPresent());
            assertTrue(fixture.store.state().interaction().busy());
        }
    }

    private static ItemEnvelope large(ItemEnvelope item) {
        return new ItemEnvelope(
                item.id(),
                item.turnId(),
                item.sequence(),
                item.kind(),
                item.schemaId(),
                item.producerId(),
                item.status(),
                new CanonicalPayload("{\"text\":\"" + "x".repeat(16 * 1024) + "\"}"),
                item.createdAt(),
                item.completedAt());
    }

    private static final class Fixture implements AutoCloseable {
        private final PresenterRpcServer server = new PresenterRpcServer();
        private final CanonicalJson json = new CanonicalJson();
        private final DesktopStore store = new DesktopStore();
        private final JavaClawClient client;
        private final ArrayDeque<Runnable> ui = new ArrayDeque<>();
        private final List<Long> cursors = new ArrayList<>();
        private final List<String> order = new ArrayList<>();
        private final AtomicBoolean finished = new AtomicBoolean(true);
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private List<ItemEnvelope> entries;
        private LongFunction<CoreRpcContracts.ItemListResult> pages = this::page;

        private Fixture(int count) throws Exception {
            entries = IntStream.rangeClosed(1, count)
                    .mapToObj(DesktopTestFixtures::item)
                    .toList();
            server.requestOverride = request -> {
                if (request.method().equals("turn/read")) {
                    order.add("turn");
                    return Optional.of(DesktopTestFixtures.turn(
                            server.thread(), finished.get() ? TurnStatus.COMPLETED : TurnStatus.RUNNING, 2));
                }
                if (request.method().equals("item/list")) {
                    order.add("items");
                    var query = json.decode(request.params(), CoreRpcContracts.ItemList.class);
                    assertEquals(100, query.limit());
                    cursors.add(query.afterSequence());
                    return Optional.of(pages.apply(query.afterSequence()));
                }
                return Optional.empty();
            };
            client = server.client(ignored -> {});
            store.update(state -> DesktopStateProjection.catalog(
                    state, List.of(server.workspace()), server.workspace(), List.of(server.thread())));
            store.update(state -> DesktopStateProjection.selectThread(state, server.thread()));
            store.update(state -> DesktopStateProjection.activeTurn(state, DesktopTestFixtures.turn()));
        }

        private CoreRpcContracts.ItemListResult page(long cursor) {
            List<ItemEnvelope> page = entries.stream()
                    .filter(item -> item.sequence() > cursor)
                    .limit(100)
                    .toList();
            return new CoreRpcContracts.ItemListResult(
                    page, page.isEmpty() ? cursor : page.getLast().sequence());
        }

        private void observe() throws InterruptedException {
            DesktopLegacyTurnObserver.observe(
                    client,
                    server.thread(),
                    DesktopTestFixtures.turn(),
                    store,
                    change -> ui.add(() -> store.update(change)),
                    cancelled::get);
        }

        private void drain() {
            while (!ui.isEmpty()) {
                ui.removeFirst().run();
            }
        }

        @Override
        public void close() throws Exception {
            client.close();
        }
    }
}
