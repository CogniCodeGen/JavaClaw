package com.javaclaw.protocol;

import java.util.Objects;

import com.javaclaw.api.CanonicalPayload;

/**
 * 所有 v2 写操作共用的乐观并发信封。
 *
 * @param idempotencyKey 调用方稳定幂等键
 * @param expectedRevision 目标版本；创建为 0
 * @param payload 业务参数
 */
public record WriteCommand(String idempotencyKey, long expectedRevision, CanonicalPayload payload) {
    /** 校验幂等键和版本。 */
    public WriteCommand {
        idempotencyKey =
                Objects.requireNonNull(idempotencyKey, "idempotencyKey").strip();
        if (idempotencyKey.isEmpty() || idempotencyKey.length() > 200) {
            throw new IllegalArgumentException("idempotencyKey length must be between 1 and 200");
        }
        if (expectedRevision < 0) {
            throw new IllegalArgumentException("expectedRevision must not be negative");
        }
        Objects.requireNonNull(payload, "payload");
    }
}
