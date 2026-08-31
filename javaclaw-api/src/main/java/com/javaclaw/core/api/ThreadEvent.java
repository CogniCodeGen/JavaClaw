package com.javaclaw.core.api;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/**
 * Thread 内有序的持久事件信封；生命周期事件可重放，token delta 不使用此模型。
 *
 * @param eventId 非空白事件标识
 * @param threadId 所属 Thread 的非空标识
 * @param turnId 关联 Turn；Thread 级事件可为 null
 * @param sequence Thread 内严格递增的持久序号，从 1 开始
 * @param type 非空白事件类型
 * @param schemaVersion 正整数事件 Schema 版本
 * @param correlationId 关联操作标识；无关联时可为 null
 * @param causationId 直接原因标识；无前置事件时可为 null
 * @param payload 不可变事件字段快照；null 归一为空 Map
 * @param timestamp 事件产生时间，非空
 */
public record ThreadEvent(
        String eventId,
        ThreadId threadId,
        TurnId turnId,
        long sequence,
        String type,
        int schemaVersion,
        String correlationId,
        String causationId,
        Map<String, String> payload,
        Instant timestamp) {
    /** 校验事件序号和 Schema 版本并复制载荷；不在此处分配序号或提交事务。 */
    public ThreadEvent {
        eventId = ThreadId.required(eventId, "eventId");
        threadId = Objects.requireNonNull(threadId, "threadId");
        if (sequence < 1 || schemaVersion < 1) {
            throw new IllegalArgumentException("sequence and schemaVersion must be positive");
        }
        type = ThreadId.required(type, "type");
        correlationId = correlationId == null ? null : correlationId.strip();
        causationId = causationId == null ? null : causationId.strip();
        payload = payload == null ? Map.of() : Map.copyOf(payload);
        timestamp = Objects.requireNonNull(timestamp, "timestamp");
    }
}
