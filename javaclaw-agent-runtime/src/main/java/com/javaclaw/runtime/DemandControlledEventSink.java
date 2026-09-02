package com.javaclaw.runtime;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;

import com.javaclaw.api.CancellationToken;
import com.javaclaw.api.TurnId;

/**
 * 显式 demand 的阻塞事件 sink。
 *
 * <p>Provider 线程在 demand 为零时等待，从源头阻止无界缓冲；每 50ms 检查取消和关闭。下游处理在锁外执行，避免重入死锁。
 */
public final class DemandControlledEventSink implements ModelEventSink, AutoCloseable {
    private final Object monitor = new Object();
    private final BiConsumer<TurnId, ModelStreamEvent> consumer;
    private final AtomicBoolean closed = new AtomicBoolean();
    private long demand;

    /**
     * 创建 sink。
     *
     * @param consumer 事件消费者
     */
    public DemandControlledEventSink(BiConsumer<TurnId, ModelStreamEvent> consumer) {
        this.consumer = Objects.requireNonNull(consumer, "consumer");
    }

    /**
     * 增加 demand，溢出时饱和为 Long.MAX_VALUE。
     *
     * @param count 正数 demand
     */
    public void request(long count) {
        if (count < 1) {
            throw new IllegalArgumentException("demand must be positive");
        }
        synchronized (monitor) {
            demand = demand > Long.MAX_VALUE - count ? Long.MAX_VALUE : demand + count;
            monitor.notifyAll();
        }
    }

    @Override
    public void publish(TurnId turnId, ModelStreamEvent event, CancellationToken cancellation)
            throws InterruptedException {
        Objects.requireNonNull(turnId, "turnId");
        Objects.requireNonNull(event, "event");
        awaitDemand(cancellation);
        consumer.accept(turnId, event);
    }

    /** 关闭并唤醒所有等待发布者。 */
    @Override
    public void close() {
        closed.set(true);
        synchronized (monitor) {
            monitor.notifyAll();
        }
    }

    private void awaitDemand(CancellationToken cancellation) throws InterruptedException {
        synchronized (monitor) {
            while (demand == 0 && !closed.get()) {
                cancellation.throwIfCancelled();
                monitor.wait(50);
            }
            cancellation.throwIfCancelled();
            if (closed.get()) {
                throw new IllegalStateException("event sink is closed");
            }
            demand--;
        }
    }
}
