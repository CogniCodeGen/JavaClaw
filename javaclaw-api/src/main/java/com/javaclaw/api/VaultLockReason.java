package com.javaclaw.api;

/** Secret Vault 锁定时可安全展示的稳定原因。 */
public enum VaultLockReason {
    /** Vault 正常可用。 */
    NONE,
    /** 当前系统没有可用的用户级凭据设施。 */
    SYSTEM_CREDENTIAL_UNAVAILABLE,
    /** H2 引用的主密钥包装不存在。 */
    MASTER_KEY_MISSING,
    /** 主密钥包装损坏或无法解封。 */
    MASTER_KEY_INVALID,
    /** Vault 已关闭，不能继续使用内存中的主密钥。 */
    CLOSED
}
