package com.javaclaw.server.security.vault;

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
}
