package com.javaclaw.sdk;

import java.time.Instant;

import com.javaclaw.sdk.model.JsonDocument;

/**
 * 非持久 Item 增量通知，使用逐 Item 的 deltaSequence 排序。
 *
 * @param threadId 所属 Thread 标识；有效服务端响应中非空
 * @param turnId 所属 Turn 标识；有效服务端响应中非空
 * @param itemId 关联 Item 标识
 * @param kind 当前流式 Item 的稳定 kind
 * @param deltaSequence 单 Item 内递增序号，不占用 Thread 持久 sequence
 * @param text 本次新增文本片段，不是累计全文
 * @param delta 新增文本和元数据的 JSON 文档
 * @param timestamp 事件发生时间
 */
public record ItemDeltaNotification(
        String threadId,
        String turnId,
        String itemId,
        String kind,
        long deltaSequence,
        String text,
        JsonDocument delta,
        Instant timestamp)
        implements ClientNotification {}
