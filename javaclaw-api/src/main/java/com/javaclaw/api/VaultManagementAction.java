package com.javaclaw.api;

/** Secret Vault 的高风险管理动作。 */
public enum VaultManagementAction {
    /** 仅重加密密文，CredentialRef 与业务 revision 保持不变。 */
    MASTER_KEY_ROTATED,
    /** 清除全部密文并使所有旧 CredentialRef 失效。 */
    VAULT_RESET
}
