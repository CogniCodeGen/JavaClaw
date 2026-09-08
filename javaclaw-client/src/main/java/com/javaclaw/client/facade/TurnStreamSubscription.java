package com.javaclaw.client.facade;

import java.util.ArrayDeque;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;

import com.javaclaw.protocol.TurnStreamRpcContracts;

/** 单订阅的有界队列及恢复协调器。reader 只入队；本对象的虚拟线程执行 reducer、补订阅和消费者。 缓冲上限同时为 256 个数据事件和 1 MiB；溢出保留已应用快照，从该 cursor 恢复。 */
public final class TurnStreamSubscription implements AutoCloseable {
    private final TurnStreamClient owner;
    private final TurnStreamState state;
    private final Consumer<TurnStreamSnapshot> updates;
    private final Consumer<Throwable> onFailure;
    private final Object signal = new Object();
    private final ArrayDeque<Queued> queue = new ArrayDeque<>();
    private final Thread worker;
    private String id = UUID.randomUUID().toString();
    private boolean closed;
    private boolean resync;
    private Throwable failure;
    private int queuedEvents;
    private long queuedBytes;

    TurnStreamSubscription(
            TurnStreamClient owner,
            TurnStreamSnapshot snapshot,
            Consumer<TurnStreamSnapshot> updates,
            Consumer<Throwable> onFailure) {
        this.owner = owner;
        state = new TurnStreamState(snapshot);
        this.updates = Objects.requireNonNull(updates, "updates");
        this.onFailure = Objects.requireNonNull(onFailure, "onFailure");
        worker = Thread.ofVirtual().name("sdk-turn-stream").unstarted(this::run);
    }

    void start() {
        worker.start();
    }

    /** @return 最后成功应用的完整快照，关闭或断线后仍可读取 */
    public TurnStreamSnapshot snapshot() {
        return state.snapshot();
    }

    /**
     * 权威 Item 已归并后释放对应 COMMITTED 暂态正文，cursor 和在途调用保持不变。
     *
     * @param persistedIds 客户端已经拥有权威正文或摘要引用的 Item ID
     */
    public void pruneCommitted(java.util.Set<com.javaclaw.api.ItemId> persistedIds) {
        state.prune(java.util.Set.copyOf(persistedIds));
    }

    void offer(TurnStreamRpcContracts.Notification notification, long bytes) {
        synchronized (signal) {
            if (closed || !notification.subscriptionId().equals(id) || resync) {
                return;
            }
            int count = Math.max(1, notification.events().size());
            if (queuedEvents + count > 256 || queuedBytes + bytes > 1024 * 1024) {
                resync = true;
                clearQueue();
            } else {
                queue.addLast(new Queued(notification, count, bytes));
                queuedEvents += count;
                queuedBytes += bytes;
            }
            signal.notifyAll();
        }
    }

    void failed(Throwable cause) {
        synchronized (signal) {
            failure = cause;
            signal.notifyAll();
        }
    }

    private void run() {
        try {
            owner.subscribeWire(this, id);
            process();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException problem) {
            if (!isClosed()) {
                onFailure.accept(problem);
            }
        } finally {
            close();
            try {
                owner.unsubscribeWire(id);
            } catch (RuntimeException ignored) {
                // 传输关闭后无法退订；连接 owner 会释放服务端会话，不能覆盖原失败。
            }
            owner.forget(id);
        }
    }

    private void process() throws InterruptedException {
        int recoveryAttempts = 0;
        while (true) {
            Queued next = take();
            if (isClosed()) {
                return;
            }
            if (next == null) {
                if (++recoveryAttempts > 3) {
                    throw new IllegalStateException("聊天流重复恢复失败，请重新连接");
                }
                recover();
                continue;
            }
            try {
                state.apply(next.notification());
            } catch (IllegalStateException gap) {
                synchronized (signal) {
                    resync = true;
                    clearQueue();
                }
                continue;
            }
            recoveryAttempts = 0;
            if (!next.notification().events().isEmpty()) {
                updates.accept(state.snapshot());
            }
        }
    }

    private Queued take() throws InterruptedException {
        synchronized (signal) {
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(15);
            while (queue.isEmpty() && !closed && !resync && failure == null) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    throw new IllegalStateException("聊天流连接失活，请重新连接");
                }
                java.util.concurrent.TimeUnit.NANOSECONDS.timedWait(signal, remaining);
            }
            if (failure != null) {
                throw new IllegalStateException("聊天流连接已断开", failure);
            }
            if (closed || resync) {
                return null;
            }
            Queued next = queue.removeFirst();
            queuedEvents -= next.count();
            queuedBytes -= next.bytes();
            return next;
        }
    }

    private void recover() {
        owner.unsubscribeWire(id);
        synchronized (signal) {
            clearQueue();
            resync = false;
            id = UUID.randomUUID().toString();
        }
        owner.subscribeWire(this, id);
    }

    private void clearQueue() {
        queue.clear();
        queuedBytes = 0;
        queuedEvents = 0;
    }

    private boolean isClosed() {
        synchronized (signal) {
            return closed;
        }
    }

    /** 异步释放订阅，调用方无需在 JavaFX 线程等待 RPC。 */
    @Override
    public void close() {
        synchronized (signal) {
            closed = true;
            clearQueue();
            signal.notifyAll();
        }
    }

    private record Queued(TurnStreamRpcContracts.Notification notification, int count, long bytes) {}
}
