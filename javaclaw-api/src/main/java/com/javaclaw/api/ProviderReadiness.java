package com.javaclaw.api;

/** Provider 本地配置检查结果。 */
public enum ProviderReadiness {
    /** 配置与 CredentialRef 均可由当前运行时解析。 */
    READY,
    /** 配置有效，但 CredentialRef 尚未由 Vault 验证。 */
    CREDENTIAL_UNVERIFIED,
    /** 配置没有 CredentialRef。 */
    CREDENTIAL_REQUIRED,
    /** CredentialRef 已失效或 Vault 不可用。 */
    CREDENTIAL_UNAVAILABLE,
    /** Provider 被停用。 */
    DISABLED,
    /** Provider 已归档。 */
    ARCHIVED,
    /** 本地配置无效。 */
    INVALID_CONFIGURATION
}
