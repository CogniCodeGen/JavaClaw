package com.javaclaw.server.persistence;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

import com.javaclaw.api.PromptOptimizationRef;

/**
 * Prompt 优化管理表保存的最小关联；状态与正文必须从 Turn/Item 权威数据投影。
 *
 * @param ref 优化任务与普通 Thread/Turn 关联
 * @param instructionRevision 内置优化说明版本
 * @param instructionDigest 内置优化说明 SHA-256
 * @param adoptedProfileRevision 已人工采纳的新 Profile revision
 * @param adoptedAt 采纳时间
 * @param createdAt 创建时间
 */
public record PromptOptimizationRecord(
        PromptOptimizationRef ref,
        String instructionRevision,
        String instructionDigest,
        Optional<Long> adoptedProfileRevision,
        Optional<Instant> adoptedAt,
        Instant createdAt) {
    /** 校验管理关联，不保存草稿正文或运行状态。 */
    public PromptOptimizationRecord {
        Objects.requireNonNull(ref, "ref");
        instructionRevision = text(instructionRevision, "instructionRevision");
        instructionDigest = digest(instructionDigest);
        adoptedProfileRevision = Objects.requireNonNull(adoptedProfileRevision, "adoptedProfileRevision");
        adoptedAt = Objects.requireNonNull(adoptedAt, "adoptedAt");
        Objects.requireNonNull(createdAt, "createdAt");
        if (adoptedProfileRevision.isPresent() != adoptedAt.isPresent()) {
            throw new IllegalArgumentException("adopted revision and time must appear together");
        }
        adoptedProfileRevision.ifPresent(revision -> {
            if (revision <= ref.sourceProfile().revision()) {
                throw new IllegalArgumentException("adopted Profile revision must be newer than source");
            }
        });
    }

    private static String text(String value, String name) {
        String checked = Objects.requireNonNull(value, name).strip();
        if (checked.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return checked;
    }

    private static String digest(String value) {
        String checked = text(value, "instructionDigest").toLowerCase(java.util.Locale.ROOT);
        if (!checked.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("instructionDigest must be SHA-256");
        }
        return checked;
    }
}
