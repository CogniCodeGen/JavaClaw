package com.javaclaw.api;

import java.time.Instant;
import java.util.Objects;

/**
 * Rollout JSONL 的完整性清单。
 *
 * @param threadId 来源 Thread
 * @param sourceRevision 导出快照要求的 Thread revision
 * @param itemCount Item 行数
 * @param lastSequence 最后 sequence；空快照为 0
 * @param finalChainHash 最后一行链式哈希；空快照为 64 个零
 * @param totalSha256 所有 Item JSONL 行及换行符的总摘要
 * @param exportedAt 导出时间
 */
public record RolloutManifest(
        ThreadId threadId,
        long sourceRevision,
        long itemCount,
        long lastSequence,
        String finalChainHash,
        String totalSha256,
        Instant exportedAt) {
    /** 校验计数、摘要和时间。 */
    public RolloutManifest {
        Objects.requireNonNull(threadId, "threadId");
        sourceRevision = Preconditions.positive(sourceRevision, "sourceRevision");
        if (itemCount < 0 || lastSequence < 0) {
            throw new IllegalArgumentException("rollout counters must not be negative");
        }
        finalChainHash = digest(finalChainHash, "finalChainHash");
        totalSha256 = digest(totalSha256, "totalSha256");
        Objects.requireNonNull(exportedAt, "exportedAt");
    }

    private static String digest(String value, String name) {
        String normalized = Objects.requireNonNull(value, name).toLowerCase(java.util.Locale.ROOT);
        if (!normalized.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(name + " must be SHA-256 hex");
        }
        return normalized;
    }
}
