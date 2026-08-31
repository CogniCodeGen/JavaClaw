package com.javaclaw.protocol;

import java.time.Instant;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 可按 sequence 恢复的持久事件信封，不承载高频 token delta。
 *
 * @param eventId 持久事件标识
 * @param threadId 所属 Thread 标识；有效服务端响应中非空
 * @param turnId 关联 Turn 标识；Thread 级事件可为 null
 * @param sequence Thread 内递增的持久事件序号；delta 不消耗此序号
 * @param type 持久事件类型标记
 * @param schemaVersion 事件 Schema 版本
 * @param correlationId 关联操作标识；可为 null
 * @param causationId 直接原因标识；可为 null
 * @param payload 结构化事件载荷；应保留未知字段以支持前向兼容
 * @param timestamp 事件发生时间
 */
public record WireEvent(
        String eventId,
        String threadId,
        String turnId,
        long sequence,
        String type,
        int schemaVersion,
        String correlationId,
        String causationId,
        JsonNode payload,
        Instant timestamp) {}
