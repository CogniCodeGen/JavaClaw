package com.javaclaw.api;

import java.time.Instant;
import java.util.Objects;

/**
 * 已提交副作用的幂等凭据。
 *
 * @param idempotencyKey 调用方提供的稳定幂等键
 * @param toolName 工具名称
 * @param requestDigest 规范请求摘要
 * @param resultDigest 规范结果摘要
 * @param committedAt 外部副作用确认时间
 */
public record EffectReceipt(
        String idempotencyKey, String toolName, String requestDigest, String resultDigest, Instant committedAt)
        implements ItemPayload {
    /** 校验摘要和提交时间。 */
    public EffectReceipt {
        idempotencyKey = Preconditions.text(idempotencyKey, "idempotencyKey");
        toolName = Preconditions.text(toolName, "toolName");
        requestDigest = digest(requestDigest, "requestDigest");
        resultDigest = digest(resultDigest, "resultDigest");
        Objects.requireNonNull(committedAt, "committedAt");
    }

    private static String digest(String value, String name) {
        String normalized = Preconditions.text(value, name).toLowerCase();
        if (!normalized.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(name + " must be SHA-256 hex");
        }
        return normalized;
    }
}
