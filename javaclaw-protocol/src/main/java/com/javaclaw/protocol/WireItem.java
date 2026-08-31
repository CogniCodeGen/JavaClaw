package com.javaclaw.protocol;

import java.time.Instant;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Unknown item kinds remain representable because payload is intentionally open.
 *
 * @param id 服务端资源标识；已保存资源非空
 * @param threadId 所属 Thread 标识；有效服务端响应中非空
 * @param turnId 所属 Turn 标识；有效服务端响应中非空
 * @param ordinal Turn 内从 1 开始的 Item 排列序号
 * @param state 服务端生命周期状态
 * @param kind 稳定类型标记；读取方应保留或忽略未知种类
 * @param payload Item 最终 JSON 内容；STARTED 可为 null，未知 kind 需保留原始 JSON
 * @param createdAt 创建时间；有效持久记录中非空
 * @param updatedAt 最近持久更新时间；已保存资源中非空
 */
public record WireItem(
        String id,
        String threadId,
        String turnId,
        long ordinal,
        String state,
        String kind,
        JsonNode payload,
        Instant createdAt,
        Instant updatedAt) {}
