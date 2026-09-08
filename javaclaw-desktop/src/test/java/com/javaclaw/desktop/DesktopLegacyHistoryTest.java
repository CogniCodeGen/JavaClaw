package com.javaclaw.desktop;

import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;

import com.javaclaw.api.CorePayloads;
import com.javaclaw.api.ItemEnvelope;
import com.javaclaw.api.MessageRole;
import com.javaclaw.api.TurnId;
import com.javaclaw.api.TurnStatus;
import com.javaclaw.desktop.state.DesktopState;
import com.javaclaw.protocol.CanonicalJson;
import com.javaclaw.protocol.CoreRpcContracts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DesktopLegacyHistoryTest {
    @Test
    void 新客户端恢复旧服务端一百零一条历史末尾的活动Turn并且不重新启动() throws Exception {
        var server = new PresenterRpcServer();
        var json = new CanonicalJson();
        List<ItemEnvelope> entries = historyAcrossTurns();
        server.completion = TurnStatus.RUNNING;
        server.requestOverride = request -> {
            if (request.method().equals("turn/read")) {
                // 首 100 条属于旧 Turn；只能使用末条历史的身份恢复，不能依赖夹具忽略查询 ID。
                assertEquals(
                        DesktopTestFixtures.turn().id(),
                        json.decode(request.params(), CoreRpcContracts.TurnQuery.class)
                                .turnId());
            }
            if (!request.method().equals("item/list")) {
                return Optional.empty();
            }
            var query = json.decode(request.params(), CoreRpcContracts.ItemList.class);
            assertEquals(100, query.limit());
            server.historyReads.incrementAndGet();
            List<ItemEnvelope> page = entries.stream()
                    .filter(item -> item.sequence() > query.afterSequence())
                    .limit(query.limit())
                    .toList();
            return Optional.of(new CoreRpcContracts.ItemListResult(
                    page,
                    page.isEmpty() ? query.afterSequence() : page.getLast().sequence()));
        };
        var ui = new ConcurrentLinkedQueue<Runnable>();
        var latest = new AtomicReference<DesktopState>();
        try (var presenter = new DesktopPresenter(server::client, ui::add, Clock.systemUTC())) {
            presenter.subscribe(latest::set);
            presenter.connect();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (System.nanoTime() < deadline) {
                Runnable action;
                while ((action = ui.poll()) != null) {
                    action.run();
                }
                if (latest.get() != null && latest.get().threads().activeTurn().isPresent()) {
                    break;
                }
                Thread.sleep(10);
            }
            assertTrue(latest.get().threads().activeTurn().isPresent());
            assertTrue(latest.get().interaction().busy());
            assertEquals(101, latest.get().transcript().nextSequence());
            assertEquals(100, latest.get().transcript().items().size());
            assertEquals(2, latest.get().transcript().items().getFirst().sequence());
            assertEquals(0, server.turnStarts.get());
            assertTrue(server.historyReads.get() >= 2);
        }
    }

    private List<ItemEnvelope> historyAcrossTurns() {
        TurnId older = TurnId.random();
        return items(1, 101).stream()
                .map(item -> item.sequence() == 101
                        ? item
                        : new ItemEnvelope(
                                item.id(),
                                older,
                                item.sequence(),
                                item.kind(),
                                item.schemaId(),
                                item.producerId(),
                                item.status(),
                                item.payload(),
                                item.createdAt(),
                                item.completedAt()))
                .toList();
    }

    @Test
    void 连续满页只保留尾部窗口并在空页停止() {
        var result = DesktopLegacyHistory.read(
                cursor -> {
                    if (cursor == 200) {
                        return new CoreRpcContracts.ItemListResult(List.of(), cursor);
                    }
                    return new CoreRpcContracts.ItemListResult(items((int) cursor + 1, 100), cursor + 100);
                },
                () -> false,
                () -> 0);
        assertEquals(200, result.nextSequence());
        assertEquals(100, result.items().size());
        assertEquals(101, result.items().getFirst().sequence());
    }

    @Test
    void 合法大尾消息被缓存预算淘汰时明确失败而不误判没有活动Turn() {
        var original = DesktopTestFixtures.item(1);
        var payload = new CanonicalJson()
                .encode(new CorePayloads.Message(
                        MessageRole.ASSISTANT, "x".repeat(4 * 1024 * 1024), List.of(), Optional.empty()));
        var large = new ItemEnvelope(
                original.id(),
                original.turnId(),
                1,
                original.kind(),
                original.schemaId(),
                original.producerId(),
                original.status(),
                payload,
                original.createdAt(),
                original.completedAt());
        var failure = assertThrows(
                IllegalStateException.class,
                () -> DesktopLegacyHistory.read(
                        cursor -> new CoreRpcContracts.ItemListResult(List.of(large), 1), () -> false, () -> 0));
        assertTrue(failure.getMessage().contains("缓存上限"));
    }

    @Test
    void 导航失效中断无进展以及持续追尾都明确停止而不返回半份历史() {
        AtomicInteger calls = new AtomicInteger();
        assertThrows(
                CancellationException.class,
                () -> DesktopLegacyHistory.read(
                        cursor -> {
                            calls.incrementAndGet();
                            return new CoreRpcContracts.ItemListResult(items(1, 100), 100);
                        },
                        () -> calls.get() > 0,
                        () -> 0));
        assertEquals(1, calls.get());
        assertThrows(
                IllegalStateException.class,
                () -> DesktopLegacyHistory.read(
                        cursor -> new CoreRpcContracts.ItemListResult(items(1, 100), cursor), () -> false, () -> 0));
        AtomicLong now = new AtomicLong();
        assertThrows(
                IllegalStateException.class,
                () -> DesktopLegacyHistory.read(
                        cursor -> {
                            now.set(TimeUnit.SECONDS.toNanos(11));
                            return new CoreRpcContracts.ItemListResult(items(1, 100), 100);
                        },
                        () -> false,
                        now::get));
        Thread.currentThread().interrupt();
        try {
            assertThrows(
                    CancellationException.class,
                    () -> DesktopLegacyHistory.read(
                            cursor -> {
                                throw new AssertionError("中断后不得再发起 RPC");
                            },
                            () -> false,
                            () -> 0));
        } finally {
            Thread.interrupted();
        }
    }

    private static List<ItemEnvelope> items(int first, int count) {
        return IntStream.range(first, first + count)
                .mapToObj(DesktopTestFixtures::item)
                .toList();
    }
}
