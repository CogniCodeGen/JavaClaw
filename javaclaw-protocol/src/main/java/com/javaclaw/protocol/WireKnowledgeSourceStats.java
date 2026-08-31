package com.javaclaw.protocol;

import java.time.Instant;

/**
 * 知识源当前可查询 generation 的安全统计；失败摘要不得包含原始文档或凭据。
 *
 * @param sourceId 知识源标识
 * @param generation 当前 generation；尚未索引时为 0
 * @param chunkCount 当前片段数
 * @param retrievalMode 检索模式
 * @param indexedAt 索引时间；尚未索引时为空
 * @param failureSummary 脱敏失败摘要
 */
public record WireKnowledgeSourceStats(
        String sourceId,
        long generation,
        long chunkCount,
        String retrievalMode,
        Instant indexedAt,
        String failureSummary) {}
