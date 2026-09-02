package com.javaclaw.api;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * Core 与 Extension 共用的 Item 持久信封。
 *
 * @param id Item 标识
 * @param turnId 所属 Turn
 * @param sequence Thread 内严格递增序号，从 1 开始
 * @param kind 稳定的展示类别
 * @param schemaId payload schema 标识
 * @param producerId 产生者；Core 固定为 {@code core}
 * @param status 生命周期状态
 * @param payload 规范化 JSON 对象
 * @param createdAt 创建时间
 * @param completedAt 终态时间；活动 Item 为空
 */
public record ItemEnvelope(
        ItemId id,
        TurnId turnId,
        long sequence,
        String kind,
        String schemaId,
        String producerId,
        ItemStatus status,
        CanonicalPayload payload,
        Instant createdAt,
        Optional<Instant> completedAt) {
    /** 校验 Item 的顺序、标识与终态时间。 */
    public ItemEnvelope {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(turnId, "turnId");
        sequence = Preconditions.positive(sequence, "sequence");
        kind = Preconditions.text(kind, "kind");
        schemaId = Preconditions.text(schemaId, "schemaId");
        producerId = Preconditions.text(producerId, "producerId");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(payload, "payload");
        Objects.requireNonNull(createdAt, "createdAt");
        completedAt = Objects.requireNonNull(completedAt, "completedAt");
        if (completedAt.filter(value -> value.isBefore(createdAt)).isPresent()) {
            throw new IllegalArgumentException("completedAt must not be before createdAt");
        }
        boolean terminal = status != ItemStatus.IN_PROGRESS;
        if (terminal != completedAt.isPresent()) {
            throw new IllegalArgumentException("completedAt presence must match terminal status");
        }
    }
}
