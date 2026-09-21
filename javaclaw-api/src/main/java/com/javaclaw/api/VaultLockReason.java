package com.javaclaw.api;

/** Secret Vault 锁定时可安全展示的稳定原因。 */
public enum VaultLockReason {
    /** Vault 正常可用。 */
    NONE,
    /** 主密钥持久化端口不可用；保留历史枚举名以兼容既有线协议。 */
    SYSTEM_CREDENTIAL_UNAVAILABLE,
    /** H2 引用的持久化主密钥不存在。 */
    MASTER_KEY_MISSING,
    /** 持久化主密钥损坏或无法加载。 */
    MASTER_KEY_INVALID,
    /** Vault 已关闭，不能继续使用内存中的主密钥。 */
    CLOSED
}
