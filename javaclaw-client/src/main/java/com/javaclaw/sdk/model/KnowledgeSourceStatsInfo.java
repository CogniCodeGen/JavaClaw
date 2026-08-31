package com.javaclaw.sdk.model;

import java.time.Instant;

/**
 * 知识源当前索引的安全统计。
 *
 * @param sourceId 知识源标识
 * @param generation 当前可查询 generation；尚未完成索引时为 0
 * @param chunkCount 当前 generation 的片段数
 * @param retrievalMode VECTOR、KEYWORD 或 UNAVAILABLE
 * @param indexedAt 当前 generation 建立时间；尚未建立时为空
 * @param failureSummary 脱敏失败摘要；无失败或服务端无记录时为空
 */
public record KnowledgeSourceStatsInfo(
        String sourceId,
        long generation,
        long chunkCount,
        String retrievalMode,
        Instant indexedAt,
        String failureSummary) {}
