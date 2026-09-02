package com.javaclaw.api;

import java.time.Instant;
import java.util.Objects;

/**
 * Prompt 优化内置说明与管理记录的可审阅来源。
 *
 * @param instructionRevision App Server 内置优化说明版本
 * @param instructionDigest 内置优化说明 SHA-256
 * @param createdAt 任务创建时间
 * @param updatedAt Turn 或采纳记录的最近变化时间
 */
public record PromptOptimizationProvenance(
        String instructionRevision, String instructionDigest, Instant createdAt, Instant updatedAt) {
    /** 校验版本、摘要与时间顺序。 */
    public PromptOptimizationProvenance {
        instructionRevision = Preconditions.text(instructionRevision, "instructionRevision");
        instructionDigest = Preconditions.digest(instructionDigest, "instructionDigest");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        if (updatedAt.isBefore(createdAt)) {
            throw new IllegalArgumentException("updatedAt must not be before createdAt");
        }
    }
}
