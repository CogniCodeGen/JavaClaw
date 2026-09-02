package com.javaclaw.api;

/** OAuth 2.1 + PKCE 授权状态。 */
public enum McpOAuthState {
    /** 等待隔离浏览器回调。 */
    PENDING,
    /** token 已密封到 Vault。 */
    AUTHORIZED,
    /** 授权或 token 交换失败。 */
    FAILED,
    /** 授权窗口已过期。 */
    EXPIRED,
    /** 用户取消。 */
    CANCELLED
}
