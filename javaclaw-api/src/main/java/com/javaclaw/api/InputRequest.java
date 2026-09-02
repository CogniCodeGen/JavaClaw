package com.javaclaw.api;

import java.time.Instant;
import java.util.Objects;

/**
 * Turn 暂停时向用户展示的结构化输入请求。
 *
 * <p>Schema 与响应都必须是规范 JSON 对象。该契约不用于收集密码、Token 或 Browser state；Secret 必须走专用 Vault 平台动作，避免明文进入 Item、RPC 重试或 Rollout。
 *
 * @param id 输入请求 ID
 * @param turnId 所属 Turn
 * @param producerId 发起请求的平台或扩展标识
 * @param prompt 用户可见的简短问题
 * @param responseSchema 允许输入的受限 JSON Schema
 * @param createdAt 创建时间
 * @param expiresAt 绝对失效时间
 */
public record InputRequest(
        String id,
        TurnId turnId,
        String producerId,
        String prompt,
        CanonicalPayload responseSchema,
        Instant createdAt,
        Instant expiresAt) {
    /** 校验标识、时间和非 Secret 契约。 */
    public InputRequest {
        id = Preconditions.identifier(id, "id");
        Objects.requireNonNull(turnId, "turnId");
        producerId = Preconditions.identifier(producerId, "producerId");
        prompt = Preconditions.text(prompt, "prompt");
        if (prompt.length() > 1000) {
            throw new IllegalArgumentException("prompt must not exceed 1000 characters");
        }
        Objects.requireNonNull(responseSchema, "responseSchema");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(expiresAt, "expiresAt");
        if (!expiresAt.isAfter(createdAt)) {
            throw new IllegalArgumentException("expiresAt must be after createdAt");
        }
    }
}
