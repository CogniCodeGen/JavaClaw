package com.javaclaw.api;

/** Secret Vault 的运行状态。 */
public enum VaultState {
    /** 主密钥可用，依赖 Secret 的能力可以运行。 */
    READY,
    /** 主密钥不可用；管理功能保留，Secret 相关能力必须 fail closed。 */
    LOCKED
}
