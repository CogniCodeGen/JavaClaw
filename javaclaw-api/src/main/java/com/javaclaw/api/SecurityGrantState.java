package com.javaclaw.api;

/** 安全授权的持久生命周期；过期由 {@code expiresAt} 与平台时钟即时判断。 */
public enum SecurityGrantState {
    /** 授权在有效期内可以参与决策。 */
    ACTIVE,
    /** 授权已写入不可逆 tombstone，不能恢复。 */
    REVOKED
}
