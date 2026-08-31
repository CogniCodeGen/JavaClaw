package com.javaclaw.agent.runtime;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Flow;
import java.util.concurrent.SubmissionPublisher;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import com.javaclaw.core.api.ItemId;
import com.javaclaw.core.api.LiveItemEvent;
import com.javaclaw.core.api.ThreadId;

/** Bounded ephemeral delta stream plus same-process active Item snapshots. */
public final class LiveItemEventBus implements com.javaclaw.agent.runtime.LiveItemSource, AutoCloseable {
    private final ExecutorService dispatcher = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor();
    private final SubmissionPublisher<LiveItemEvent> publisher;
    private final ConcurrentHashMap<ItemId, LiveItemEvent> latest = new ConcurrentHashMap<>();
    private final AtomicLong dropped = new AtomicLong();

    /** 使用默认有界缓冲创建瞬时 Item 流；其生命周期由 Runtime 管理。 */
    public LiveItemEventBus() {
        this(1_024);
    }

    /** 创建正数容量的瞬时事件总线；溢出不阻塞生产者，活动快照用于重连恢复。 */
    public LiveItemEventBus(int capacity) {
        if (capacity < 1) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        publisher = new SubmissionPublisher<>(dispatcher, capacity);
    }

    @Override
    public void subscribe(Flow.Subscriber<? super LiveItemEvent> subscriber) {
        publisher.subscribe(Objects.requireNonNull(subscriber, "subscriber"));
    }

    /** 更新活动 Item 内存快照并非阻塞发布；终态移除对应快照，不消费 Thread 持久序号。 */
    public void publish(LiveItemEvent event) {
        Objects.requireNonNull(event, "event");
        if (event.phase() == LiveItemEvent.Phase.COMPLETED || event.phase() == LiveItemEvent.Phase.FAILED) {
            latest.remove(event.itemId());
        } else {
            latest.put(event.itemId(), event);
        }
        publisher.offer(event, 0, TimeUnit.MILLISECONDS, (subscriber, value) -> {
            dropped.incrementAndGet();
            return false;
        });
    }

    @Override
    public List<LiveItemEvent> active(ThreadId threadId) {
        return latest.values().stream()
                .filter(value -> value.threadId().equals(threadId))
                .sorted(java.util.Comparator.comparing(value -> value.itemId().value()))
                .toList();
    }

    /** 返回瞬时事件因背压丢弃的累计次数；客户端应按游标与活动快照重新同步。 */
    public long droppedEventCount() {
        return dropped.get();
    }

    @Override
    public void close() {
        latest.clear();
        publisher.close();
        dispatcher.shutdownNow();
    }
}
