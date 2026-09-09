package com.javaclaw.desktop;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import com.javaclaw.api.ItemHistoryResult;
import com.javaclaw.api.ItemId;
import com.javaclaw.api.TurnStatus;
import com.javaclaw.api.TurnStreamCall;
import com.javaclaw.api.TurnStreamEvent;
import com.javaclaw.api.TurnStreamKind;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DesktopTurnStreamCoordinatorTest {
    @Test
    void 迟到对账不能覆盖已显示的新正文() throws Exception {
        delayedReconciliation(false);
    }

    @Test
    void 迟到对账不能覆盖已显示的终态流快照() throws Exception {
        delayedReconciliation(true);
    }

    private void delayedReconciliation(boolean terminal) throws Exception {
        PresenterRpcServer server = server();
        DelayedRead gate = new DelayedRead();
        server.requestOverride = request -> {
            if (request.method().equals("turn/read")) {
                gate.read();
            }
            return Optional.empty();
        };
        DesktopStore store = selected();
        ConcurrentLinkedQueue<Runnable> ui = new ConcurrentLinkedQueue<>();
        AtomicInteger completedUiTasks = new AtomicInteger();
        try (var workers = Executors.newVirtualThreadPerTaskExecutor();
                var client = server.client(ignored -> {});
                var owner = new DesktopTurnStreamCoordinator(
                        store,
                        action -> ui.add(() -> {
                            action.run();
                            completedUiTasks.incrementAndGet();
                        }),
                        workers)) {
            owner.observe(client, server.thread(), DesktopTestFixtures.turn());
            FxTestSupport.await(() -> server.streamSubscription != null && gate.reads.get() >= 1);
            TurnStreamCall call = new TurnStreamCall(1, "attempt", ItemId.random());
            server.emitStream(List.of(
                    event(call, "1", "START", TurnStreamKind.STARTED, "", 0),
                    event(call, "2", "1", TurnStreamKind.TEXT_DELTA, "此前", 0)));
            awaitCursor(store, ui, "2");
            assertTrue(gate.entered.await(3, TimeUnit.SECONDS));
            server.emitStream(newerEvents(call, terminal));
            String cursor = terminal ? "5" : "3";
            awaitCursor(store, ui, cursor);
            AtomicInteger updates = new AtomicInteger();
            store.subscribe(ignored -> updates.incrementAndGet());
            int before = updates.get();
            int completedBefore = completedUiTasks.get();
            gate.release.countDown();
            // 等待迟到对账实际执行；相同事实不再通知 Store，不能用多余重绘证明回调已经到达。
            FxTestSupport.await(() -> {
                drain(ui);
                return completedUiTasks.get() > completedBefore;
            });
            assertEquals(before, updates.get());
            var observed = store.state().transcript().stream().orElseThrow();
            assertEquals(cursor, observed.cursor());
            assertEquals("此前新字", observed.messages().getFirst().text());
            assertEquals(terminal, observed.terminal().isPresent());
        } finally {
            gate.release.countDown();
        }
    }

    @Test
    void 终态已到但最终事实尚未到时重连仍从原游标补齐且不重启Turn() throws Exception {
        PresenterRpcServer first = server();
        PresenterRpcServer second = server();
        DesktopStore store = selected();
        ConcurrentLinkedQueue<Runnable> ui = new ConcurrentLinkedQueue<>();
        try (var workers = Executors.newVirtualThreadPerTaskExecutor();
                var before = first.client(ignored -> {});
                var after = second.client(ignored -> {});
                var owner = new DesktopTurnStreamCoordinator(store, ui::add, workers)) {
            assertTrue(owner.observe(before, first.thread(), DesktopTestFixtures.turn()));
            FxTestSupport.await(() -> first.streamSubscription != null);
            first.emitStream(List.of(new TurnStreamEvent(
                    DesktopTestFixtures.turn().id(),
                    "finished",
                    "START",
                    new TurnStreamEvent.Data(
                            TurnStreamKind.TURN_FINISHED,
                            Optional.empty(),
                            "",
                            0,
                            Optional.of(3L),
                            Optional.of(TurnStatus.COMPLETED)))));
            FxTestSupport.await(() -> {
                drain(ui);
                return store.state().transcript().stream()
                        .flatMap(value -> value.terminal())
                        .isPresent();
            });
            owner.reconnecting();
            second.completion = TurnStatus.COMPLETED;
            second.history = () -> new ItemHistoryResult(List.of(), 3, false);
            owner.connected(after);
            FxTestSupport.await(() -> second.streamSubscription != null);
            assertEquals("finished", second.streamSubscription.afterCursor());
            FxTestSupport.await(() -> {
                drain(ui);
                return store.state().transcript().nextSequence() == 3;
            });
            assertEquals(0, first.turnStarts.get() + second.turnStarts.get());
            FxTestSupport.await(() -> second.streamReleases.get() == 1);
        }
    }

    @Test
    void 用户切换后迟到的权威查询不写入新页面游标或活动Turn() throws Exception {
        PresenterRpcServer server = server();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch released = new CountDownLatch(1);
        server.history = () -> {
            entered.countDown();
            try {
                if (!released.await(5, TimeUnit.SECONDS)) {
                    throw new AssertionError("测试未释放延迟查询");
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new AssertionError(interrupted);
            }
            return new ItemHistoryResult(List.of(), 900, false);
        };
        DesktopStore store = selected();
        ConcurrentLinkedQueue<Runnable> ui = new ConcurrentLinkedQueue<>();
        try (var workers = Executors.newVirtualThreadPerTaskExecutor();
                var client = server.client(ignored -> {});
                var owner = new DesktopTurnStreamCoordinator(store, ui::add, workers)) {
            owner.observe(client, server.thread(), DesktopTestFixtures.turn());
            assertTrue(entered.await(3, TimeUnit.SECONDS));
            owner.selectionChanged();
            store.update(state -> DesktopStateProjection.selectThread(state, server.thread()));
            released.countDown();
            FxTestSupport.await(() -> server.streamReleases.get() > 0);
            drain(ui);
            assertEquals(0, store.state().transcript().nextSequence());
            assertTrue(store.state().threads().activeTurn().isEmpty());
        } finally {
            released.countDown();
        }
    }

    @Test
    void 突发正文在一个UI提交中合并且排队的旧提交不会清除新选择() throws Exception {
        PresenterRpcServer server = server();
        DesktopStore store = selected();
        ConcurrentLinkedQueue<Runnable> ui = new ConcurrentLinkedQueue<>();
        try (var workers = Executors.newVirtualThreadPerTaskExecutor();
                var client = server.client(ignored -> {});
                var owner = new DesktopTurnStreamCoordinator(store, ui::add, workers)) {
            owner.observe(client, server.thread(), DesktopTestFixtures.turn());
            FxTestSupport.await(() -> server.streamSubscription != null);
            TurnStreamCall call = new TurnStreamCall(1, "attempt", ItemId.random());
            server.emitStream(List.of(
                    event(call, "1", "START", TurnStreamKind.STARTED, "", 0),
                    event(call, "2", "1", TurnStreamKind.TEXT_DELTA, "正文", 0),
                    event(call, "3", "2", TurnStreamKind.TEXT_DELTA, "末尾", 2)));
            FxTestSupport.await(() -> {
                drain(ui);
                return store.state().transcript().stream()
                        .filter(value -> value.cursor().equals("3"))
                        .isPresent();
            });
            assertEquals(
                    "正文末尾",
                    store.state().transcript().stream()
                            .orElseThrow()
                            .messages()
                            .getFirst()
                            .text());
            owner.selectionChanged();
            store.update(state -> DesktopStateProjection.selectThread(state, server.thread()));
            drain(ui);
            assertTrue(store.state().transcript().stream().isEmpty());
        }
    }

    private static TurnStreamEvent event(
            TurnStreamCall call, String cursor, String previous, TurnStreamKind kind, String text, long offset) {
        return new TurnStreamEvent(
                DesktopTestFixtures.turn().id(),
                cursor,
                previous,
                new TurnStreamEvent.Data(kind, Optional.of(call), text, offset, Optional.empty(), Optional.empty()));
    }

    private static List<TurnStreamEvent> newerEvents(TurnStreamCall call, boolean terminal) {
        var delta = event(call, "3", "2", TurnStreamKind.TEXT_DELTA, "新字", 2);
        if (!terminal) {
            return List.of(delta);
        }
        var finished = new TurnStreamEvent(
                DesktopTestFixtures.turn().id(),
                "5",
                "4",
                new TurnStreamEvent.Data(
                        TurnStreamKind.TURN_FINISHED,
                        Optional.empty(),
                        "",
                        0,
                        Optional.of(0L),
                        Optional.of(TurnStatus.CANCELLED)));
        return List.of(delta, event(call, "4", "3", TurnStreamKind.CLOSED, "", 0), finished);
    }

    private static void awaitCursor(DesktopStore store, ConcurrentLinkedQueue<Runnable> ui, String cursor) {
        FxTestSupport.await(() -> {
            drain(ui);
            return store.state().transcript().stream()
                    .filter(value -> value.cursor().equals(cursor))
                    .isPresent();
        });
    }

    private static final class DelayedRead {
        private final AtomicInteger reads = new AtomicInteger();
        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);

        private void read() {
            if (reads.incrementAndGet() == 2) {
                entered.countDown();
                try {
                    assertTrue(release.await(5, TimeUnit.SECONDS));
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(interrupted);
                }
            }
        }
    }

    private static PresenterRpcServer server() {
        PresenterRpcServer server = new PresenterRpcServer();
        server.streamEnabled = true;
        server.completion = TurnStatus.RUNNING;
        return server;
    }

    private static DesktopStore selected() {
        DesktopStore store = new DesktopStore();
        store.update(state -> DesktopStateProjection.catalog(
                state,
                List.of(DesktopTestFixtures.workspace()),
                DesktopTestFixtures.workspace(),
                List.of(DesktopTestFixtures.thread())));
        store.update(state -> DesktopStateProjection.selectThread(state, DesktopTestFixtures.thread()));
        return store;
    }

    private static void drain(ConcurrentLinkedQueue<Runnable> ui) {
        Runnable action;
        while ((action = ui.poll()) != null) {
            action.run();
        }
    }
}
