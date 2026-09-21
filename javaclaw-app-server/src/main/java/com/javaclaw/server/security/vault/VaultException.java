package com.javaclaw.server.security.vault;

import com.javaclaw.api.VaultLockReason;

/** Secret Vault 不可用、密文损坏或持久化失败。 */
public final class VaultException extends RuntimeException {
    /**
     * 创建稳定脱敏异常。
     *
     * @param message 不包含 Secret 的说明
     */
    public VaultException(String message) {
        super(message);
    }

    /**
     * 创建带内部原因的稳定脱敏异常。
     *
     * @param message 不包含 Secret 的说明
     * @param cause 内部原因
     */
    public VaultException(String message, Throwable cause) {
        super(message, cause);
    }

    static VaultException locked(VaultLockReason reason) {
        String message =
                switch (reason) {
                    case SYSTEM_CREDENTIAL_UNAVAILABLE -> "Secret Vault 已锁定：主密钥存储不可用";
                    case MASTER_KEY_MISSING -> "Secret Vault 已锁定：找不到持久化主密钥";
                    case MASTER_KEY_INVALID -> "Secret Vault 已锁定：持久化主密钥无效或无法加载";
                    case CLOSED -> "Secret Vault 已关闭";
                    case NONE -> "Secret Vault 已锁定";
                };
        return new VaultException(message);
    }
}
