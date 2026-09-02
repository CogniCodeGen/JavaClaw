package com.javaclaw.extension.spi;

import java.util.Objects;

/**
 * 扩展进程内写命令的幂等与乐观锁身份。
 *
 * @param idempotencyKey 全局幂等键
 * @param expectedRevision 目标资源版本；创建为 0
 */
public record ExtensionJobMutation(String idempotencyKey, long expectedRevision) {
    /** 校验命令身份。 */
    public ExtensionJobMutation {
        idempotencyKey =
                Objects.requireNonNull(idempotencyKey, "idempotencyKey").strip();
        if (idempotencyKey.isEmpty() || idempotencyKey.length() > 200) {
            throw new IllegalArgumentException("idempotencyKey length must be between 1 and 200");
        }
        if (expectedRevision < 0) {
            throw new IllegalArgumentException("expectedRevision must not be negative");
        }
    }
}
