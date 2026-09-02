package com.javaclaw.client;

import java.util.Objects;
import java.util.UUID;

/**
 * Protocol v2 写命令的调用选项。
 *
 * @param idempotencyKey 跨重试保持不变的键
 * @param expectedRevision 目标乐观锁版本；创建为 0
 */
public record CommandOptions(String idempotencyKey, long expectedRevision) {
    /** 校验键和 revision。 */
    public CommandOptions {
        idempotencyKey =
                Objects.requireNonNull(idempotencyKey, "idempotencyKey").strip();
        if (idempotencyKey.isEmpty() || idempotencyKey.length() > 200) {
            throw new IllegalArgumentException("idempotencyKey length must be between 1 and 200");
        }
        if (expectedRevision < 0) {
            throw new IllegalArgumentException("expectedRevision must not be negative");
        }
    }

    /**
     * 为一次新逻辑命令生成键；网络重试必须复用返回对象。
     *
     * @param expectedRevision 目标版本
     * @return 新选项
     */
    public static CommandOptions create(long expectedRevision) {
        return new CommandOptions(UUID.randomUUID().toString(), expectedRevision);
    }
}
