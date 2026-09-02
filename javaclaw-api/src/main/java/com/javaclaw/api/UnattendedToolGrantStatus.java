package com.javaclaw.api;

/**
 * 无人值守授权及其独立使用账本投影。
 *
 * @param grant 授权快照
 * @param consumedUses 已消费次数，包含 UNKNOWN_OUTCOME
 * @param remainingUses 剩余次数
 */
public record UnattendedToolGrantStatus(UnattendedToolGrant grant, int consumedUses, int remainingUses) {
    /** 校验使用计数与授权上限一致。 */
    public UnattendedToolGrantStatus {
        if (grant == null) {
            throw new NullPointerException("grant");
        }
        if (consumedUses < 0
                || consumedUses > grant.maximumUses()
                || remainingUses != grant.maximumUses() - consumedUses) {
            throw new IllegalArgumentException("grant usage counters are inconsistent");
        }
    }
}
