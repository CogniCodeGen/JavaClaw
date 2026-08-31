package com.javaclaw.sdk.model;

import java.time.Instant;

/**
 * Item 持久生命周期与类型化内容，用于恢复和客户端渲染。
 *
 * @param id 服务端资源标识；已保存资源非空
 * @param threadId 所属 Thread 标识；有效服务端响应中非空
 * @param turnId 所属 Turn 标识；有效服务端响应中非空
 * @param ordinal Turn 内从 1 开始的 Item 排列序号
 * @param state 服务端生命周期状态
 * @param content 已知类型的 SDK 内容或 UnknownItemContent；STARTED 可为空
 * @param createdAt 创建时间；有效持久记录中非空
 * @param updatedAt 最近持久更新时间；已保存资源中非空
 */
public record ItemInfo(
        String id,
        String threadId,
        String turnId,
        long ordinal,
        String state,
        ItemContent content,
        Instant createdAt,
        Instant updatedAt) {}
