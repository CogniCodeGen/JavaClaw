package com.javaclaw.protocol;

/** 会话 Secret envelope 无效、被篡改、重放或无法使用。 */
public final class SecretSealingException extends IllegalArgumentException {
    /**
     * 创建不包含 Secret 或底层加密详情的稳定异常。
     *
     * @param message 脱敏说明
     */
    public SecretSealingException(String message) {
        super(message);
    }

    /**
     * 创建带内部原因的脱敏异常。
     *
     * @param message 脱敏说明
     * @param cause 内部加密失败
     */
    public SecretSealingException(String message, Throwable cause) {
        super(message, cause);
    }
}
