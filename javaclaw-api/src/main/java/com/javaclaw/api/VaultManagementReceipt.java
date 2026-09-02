package com.javaclaw.api;

import java.time.Instant;
import java.util.Objects;

/**
 * Vault 高风险管理命令的脱敏、可幂等重放回执。
 *
 * @param action 已提交动作
 * @param affectedCredentialCount 被重加密或失效的凭据数量
 * @param completedAt H2 提交时间
 */
public record VaultManagementReceipt(VaultManagementAction action, long affectedCredentialCount, Instant completedAt) {
    /** 校验回执不包含 Secret 且计数有效。 */
    public VaultManagementReceipt {
        Objects.requireNonNull(action, "action");
        affectedCredentialCount = Preconditions.nonNegative(affectedCredentialCount, "affectedCredentialCount");
        Objects.requireNonNull(completedAt, "completedAt");
    }
}
