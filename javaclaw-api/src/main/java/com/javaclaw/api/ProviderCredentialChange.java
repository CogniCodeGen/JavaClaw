package com.javaclaw.api;

/** 完整 Provider 配置中明确的凭据处理意图；不携带任意 Vault 引用。 */
public enum ProviderCredentialChange {
    /** 保留当前绑定；无鉴权且无绑定时保持空值。 */
    KEEP,
    /** 使用当前操作专用的密封秘密首次写入或轮换。 */
    REPLACE,
    /** 在最终配置提交时清除已有绑定和秘密。 */
    CLEAR
}
