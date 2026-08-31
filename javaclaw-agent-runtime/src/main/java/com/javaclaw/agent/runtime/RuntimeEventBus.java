package com.javaclaw.agent.runtime;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Flow;
import java.util.concurrent.SubmissionPublisher;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import com.javaclaw.core.api.ThreadEvent;

/** Bounded live projection. Durable replay always comes from the ThreadJournal. */
public final class RuntimeEventBus implements Flow.Publisher<ThreadEvent>, AutoCloseable {
    private final SubmissionPublisher<ThreadEvent> publisher;
    private final ExecutorService dispatcher;
    private final AtomicLong droppedEvents = new AtomicLong();
    private final ConcurrentHashMap<Flow.Subscriber<? super ThreadEvent>, ConcurrentHashMap<String, AtomicBoolean>>
            pendingResync = new ConcurrentHashMap<>();

    /** 使用每订阅者 256 条缓冲和虚拟线程分发；慢客户端不得阻塞 Turn。 */
    public RuntimeEventBus() {
        this(256);
    }

    /** 创建正数容量的有界总线；close 关闭分发器，丢失的实时事件由 Journal 重放恢复。 */
    public RuntimeEventBus(int capacity) {
        if (capacity < 1) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        dispatcher = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor();
        publisher = new SubmissionPublisher<>(dispatcher, capacity);
    }

    @Override
    public void subscribe(Flow.Subscriber<? super ThreadEvent> subscriber) {
        publisher.subscribe(Objects.requireNonNull(subscriber, "subscriber"));
    }

    /** 非阻塞发布持久事件的实时投影；缓冲满时计数并异步通知支持 resync 的订阅者。 */
    public void publish(ThreadEvent event) {
        Objects.requireNonNull(event, "event");
        publisher.offer(event, 0, TimeUnit.MILLISECONDS, (subscriber, dropped) -> {
            droppedEvents.incrementAndGet();
            if (subscriber instanceof RuntimeStreams.ResyncAwareSubscriber aware) {
                scheduleResync(subscriber, aware, dropped);
            }
            return false;
        });
    }

    private void scheduleResync(
            Flow.Subscriber<? super ThreadEvent> subscriber,
            RuntimeStreams.ResyncAwareSubscriber aware,
            ThreadEvent dropped) {
        String threadId = dropped.threadId().value();
        ConcurrentHashMap<String, AtomicBoolean> subscriberThreads =
                pendingResync.computeIfAbsent(subscriber, ignored -> new ConcurrentHashMap<>());
        AtomicBoolean scheduled = subscriberThreads.computeIfAbsent(threadId, ignored -> new AtomicBoolean());
        if (!scheduled.compareAndSet(false, true)) {
            return;
        }
        try {
            dispatcher.submit(() -> {
                try {
                    aware.resyncRequired(threadId, Math.max(0, dropped.sequence() - 1));
                } finally {
                    scheduled.set(false);
                    subscriberThreads.remove(threadId, scheduled);
                    if (subscriberThreads.isEmpty()) {
                        pendingResync.remove(subscriber, subscriberThreads);
                    }
                }
            });
        } catch (RuntimeException rejected) {
            scheduled.set(false);
            subscriberThreads.remove(threadId, scheduled);
            if (subscriberThreads.isEmpty()) {
                pendingResync.remove(subscriber, subscriberThreads);
            }
        }
    }

    /** 返回因背压丢弃的投影投递累计次数；持久 Journal 中的原事件不受影响。 */
    public long droppedEventCount() {
        return droppedEvents.get();
    }

    @Override
    public void close() {
        publisher.close();
        pendingResync.clear();
        dispatcher.shutdownNow();
    }
}
