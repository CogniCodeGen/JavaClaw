package com.javaclaw.core.api;

import java.time.Instant;
import java.util.Objects;

/**
 * In-memory Item stream, separate from the durable Thread event journal.
 *
 * @param threadId 所属 Thread 的非空标识
 * @param turnId 所属 Turn 的非空标识
 * @param itemId 所属 Item 的非空标识
 * @param kind 非空白 Item 类型标记
 * @param deltaSequence 单 Item 内的非负增量序号，不是 Thread 的持久 sequence
 * @param delta 文本增量；生命周期通知可为 null
 * @param phase 非空生命周期阶段
 * @param timestamp 事件产生时间，非空
 */
public record LiveItemEvent(
        ThreadId threadId,
        TurnId turnId,
        ItemId itemId,
        String kind,
        long deltaSequence,
        ItemDelta delta,
        Phase phase,
        Instant timestamp) {
    /** 内存 Item 流的生命周期标记；用于重连时恢复正在生成的内容。 */
    public enum Phase {
        STARTED,
        DELTA,
        COMPLETED,
        FAILED
    }

    /** 校验关联标识与单 Item 序号；生命周期通知允许没有 delta 载荷。 */
    public LiveItemEvent {
        threadId = Objects.requireNonNull(threadId, "threadId");
        turnId = Objects.requireNonNull(turnId, "turnId");
        itemId = Objects.requireNonNull(itemId, "itemId");
        kind = ThreadId.required(kind, "kind");
        if (deltaSequence < 0) {
            throw new IllegalArgumentException("deltaSequence is negative");
        }
        phase = Objects.requireNonNull(phase, "phase");
        timestamp = Objects.requireNonNull(timestamp, "timestamp");
    }
}
