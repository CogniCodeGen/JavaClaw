package com.javaclaw.api;

import java.time.Instant;
import java.util.Objects;

/**
 * 可供设置页和 Diagnostics 展示的脱敏 Vault 状态。
 *
 * @param state 是否可执行 Secret 操作
 * @param reason 锁定原因；READY 时必须为 NONE
 * @param credentialCount 已配置 Secret 数量
 * @param oldKeyCleanupPending 是否仍需清理旧主密钥包装
 * @param checkedAt 状态采样时间
 */
public record VaultStatus(
        VaultState state,
        VaultLockReason reason,
        long credentialCount,
        boolean oldKeyCleanupPending,
        Instant checkedAt) {
    /** 校验状态组合和非敏感计数。 */
    public VaultStatus {
        state = Objects.requireNonNull(state, "state");
        reason = Objects.requireNonNull(reason, "reason");
        credentialCount = Preconditions.nonNegative(credentialCount, "credentialCount");
        checkedAt = Objects.requireNonNull(checkedAt, "checkedAt");
        if ((state == VaultState.READY) != (reason == VaultLockReason.NONE)) {
            throw new IllegalArgumentException("Vault state and lock reason disagree");
        }
    }
}
