package com.javaclaw.desktop;

import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import com.javaclaw.api.AgentTurn;
import com.javaclaw.api.ConversationThread;
import com.javaclaw.client.facade.TurnStreamSnapshot;
import com.javaclaw.client.facade.TurnStreamSubscription;
import com.javaclaw.client.sdk.JavaClawClient;
import com.javaclaw.desktop.state.ConnectionState;
import com.javaclaw.desktop.state.DesktopState;
import com.javaclaw.protocol.CoreRpcContracts;

/**
 * SDK 公开流的 Desktop owner；通知只合并 Java 快照，50ms 时钟最多排队一个 UI 提交。
 *
 * <p>审批和结构化事实仍从权威查询读取，不从 Markdown 推断。1s 审批对账独立于正文流，Item 在提交水位变化 或5s对账时读取；传输恢复只重订阅日志，绝不重新启动 Turn。
 */
final class DesktopTurnStreamCoordinator implements AutoCloseable {
    private final DesktopStore store;
    private final Consumer<Runnable> ui;
    private final ExecutorService workers;
    private final ScheduledExecutorService pulse =
            Executors.newSingleThreadScheduledExecutor(Thread.ofVirtual().factory());
    private final AtomicReference<TurnStreamSnapshot> pending = new AtomicReference<>();
    private volatile long epoch;
    private volatile TurnStreamSnapshot snapshot;
    private volatile TurnStreamSubscription subscription;
    private volatile ConversationThread thread;
    private volatile AgentTurn turn;
    private volatile boolean queued;
    private volatile boolean closed;
    private long itemCursor;

    DesktopTurnStreamCoordinator(DesktopStore store, Consumer<Runnable> ui, ExecutorService workers) {
        this.store = store;
        this.ui = ui;
        this.workers = workers;
        pulse.scheduleAtFixedRate(this::flush, 50, 50, TimeUnit.MILLISECONDS);
    }

    synchronized boolean observe(JavaClawClient client, ConversationThread selected, AgentTurn started) {
        if (!client.streams().available()) {
            return false;
        }
        stop(false);
        thread = selected;
        turn = started;
        itemCursor = store.state().transcript().nextSequence();
        long expected = epoch;
        subscription = client.streams()
                .subscribe(started.id(), value -> accept(expected, value), failure -> failed(expected, failure));
        workers.submit(() -> reconcile(expected, client));
        return true;
    }

    /** 导航可从历史重订阅；重连同一个 Turn 时优先保留已应用 cursor，绝不重放业务命令。 */
    synchronized boolean restore(JavaClawClient client, ConversationThread selected, AgentTurn active) {
        if (!client.streams().available()) {
            return false;
        }
        if (snapshot == null
                || !snapshot.turnId().equals(active.id())
                || thread == null
                || !thread.id().equals(selected.id())) {
            return observe(client, selected, active);
        }
        turn = active;
        connected(client);
        return true;
    }

    void reconnecting() {
        stop(true);
    }

    synchronized void connected(JavaClawClient client) {
        TurnStreamSnapshot previous = snapshot;
        if (closed || previous == null || thread == null || !client.streams().available()) {
            return;
        }
        boolean selected = store.state()
                .threads()
                .selectedThread()
                .filter(value -> value.id().equals(thread.id()))
                .isPresent();
        if (!selected) {
            stop(false);
            return;
        }
        long expected = epoch;
        ui.accept(() -> {
            if (expected == epoch) {
                store.update(state -> DesktopStateProjection.activeTurn(state, turn));
            }
        });
        subscription = client.streams()
                .resume(previous, value -> accept(expected, value), failure -> failed(expected, failure));
        workers.submit(() -> reconcile(expected, client));
    }

    void selectionChanged() {
        stop(false);
    }

    private synchronized void accept(long expected, TurnStreamSnapshot value) {
        if (expected == epoch && !closed) {
            snapshot = value;
            pending.set(value);
        }
    }

    private void flush() {
        if (queued || closed || pending.get() == null) {
            return;
        }
        queued = true;
        long expected = epoch;
        ui.accept(() -> {
            try {
                if (expected != epoch) {
                    return;
                }
                TurnStreamSnapshot value = pending.getAndSet(null);
                if (expected == epoch && value != null && !closed) {
                    store.update(state -> streamState(state, value));
                }
            } finally {
                queued = false;
            }
        });
    }

    private DesktopState streamState(DesktopState state, TurnStreamSnapshot value) {
        if (thread == null
                || turn == null
                || !value.turnId().equals(turn.id())
                || state.threads()
                        .selectedThread()
                        .filter(item -> item.id().equals(thread.id()))
                        .isEmpty()) {
            return state;
        }
        return DesktopStateProjection.transcript(state, state.transcript().stream(value));
    }

    private void reconcile(long expected, JavaClawClient client) {
        long lastRead = 0;
        long committed = -1;
        ConversationThread observedThread = thread;
        AgentTurn observedTurn = turn;
        while (expected == epoch && !closed) {
            try {
                TurnStreamSnapshot current = snapshot;
                long latest = committedSequence(current);
                AgentTurn observed = client.turns().read(observedTurn.id());
                var approvals = client.approvals().list(Optional.of(observedTurn.id()), false);
                boolean readItems = latest != committed
                        || System.nanoTime() - lastRead >= 5_000_000_000L
                        || DesktopStateProjection.terminal(observed.status());
                var history = readItems ? client.items().history(observedThread.id(), 0, 100) : null;
                if (!acceptHistory(expected, history, observed)) {
                    return;
                }
                if (readItems) {
                    lastRead = System.nanoTime();
                    committed = latest;
                }
                boolean done = current != null
                        && current.finalItemSequence()
                                .filter(value -> itemCursor >= value)
                                .isPresent();
                publishObservation(expected, new Observation(observed, approvals, history, done, itemCursor));
                if (done) {
                    return;
                }
                Thread.sleep(1000);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            } catch (RuntimeException failure) {
                failed(expected, failure);
                return;
            }
        }
    }

    private static long committedSequence(TurnStreamSnapshot snapshot) {
        return snapshot == null
                ? 0
                : snapshot.messages().stream()
                        .flatMap(message -> message.itemSequence().stream())
                        .mapToLong(Long::longValue)
                        .max()
                        .orElse(0);
    }

    private synchronized boolean acceptHistory(
            long expected, com.javaclaw.api.ItemHistoryResult history, AgentTurn observed) {
        if (expected != epoch || closed) {
            return false;
        }
        turn = observed;
        if (history != null) {
            itemCursor = history.latestSequence();
            if (subscription != null) {
                subscription.pruneCommitted(history.items().stream()
                        .map(com.javaclaw.api.ItemHistoryEntry::id)
                        .collect(java.util.stream.Collectors.toSet()));
            }
        }
        return true;
    }

    private void publishObservation(long expected, Observation value) {
        ui.accept(() -> {
            if (expected != epoch || closed) {
                return;
            }
            store.update(state -> {
                var page = new CoreRpcContracts.ItemListResult(
                        java.util.List.of(),
                        Math.max(value.cursor(), state.transcript().nextSequence()));
                var next = DesktopStateProjection.observation(state, value.turn(), page, value.approvals());
                if (value.history() != null) {
                    next = DesktopStateProjection.transcript(
                            next, next.transcript().committed(value.history()));
                }
                // RPC 等待期间正文仍会前进；UI 只提交此刻最新快照，不能用查询前的旧正文覆盖已经绘制的增量或终态。
                TurnStreamSnapshot latest = snapshot;
                return latest == null ? next : streamState(next, latest);
            });
            // 先完成最终事实替换，再使旧回调失效；不能在排队后提前推进 epoch 丢掉终态。
            if (value.done()) {
                stop(true);
            }
        });
    }

    private record Observation(
            AgentTurn turn,
            java.util.List<com.javaclaw.api.ApprovalRecord> approvals,
            com.javaclaw.api.ItemHistoryResult history,
            boolean done,
            long cursor) {}

    private synchronized void failed(long expected, Throwable failure) {
        if (expected != epoch || closed) {
            return;
        }
        stop(true);
        long disconnected = epoch;
        ui.accept(() -> {
            if (epoch == disconnected && !closed) {
                store.update(state ->
                        DesktopStateProjection.connection(state, ConnectionState.failed("聊天连接已中断；重新连接会从已接收正文继续")));
            }
        });
    }

    private synchronized void stop(boolean preserve) {
        epoch++;
        pending.set(null);
        TurnStreamSubscription old = subscription;
        subscription = null;
        if (old != null) {
            snapshot = old.snapshot();
            old.close();
        }
        if (!preserve) {
            snapshot = null;
            thread = null;
            turn = null;
        }
    }

    @Override
    public void close() {
        closed = true;
        stop(false);
        pulse.shutdownNow();
    }
}
