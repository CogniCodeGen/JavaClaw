package com.javaclaw.nativehost.credential;

/** 操作系统凭据设施不可用、拒绝访问或返回无效数据。 */
public final class MasterKeyProtectionException extends RuntimeException {
    /**
     * 创建不包含敏感输出的异常。
     *
     * @param message 稳定且已脱敏的说明
     */
    public MasterKeyProtectionException(String message) {
        super(message);
    }

    /**
     * 创建带内部原因但不拼接其消息的异常。
     *
     * @param message 稳定且已脱敏的说明
     * @param cause 内部原因
     */
    public MasterKeyProtectionException(String message, Throwable cause) {
        super(message, cause);
    }
}
