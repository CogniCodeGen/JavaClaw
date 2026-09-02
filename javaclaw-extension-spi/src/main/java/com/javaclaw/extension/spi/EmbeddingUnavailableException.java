package com.javaclaw.extension.spi;

/** Embedding Provider 未配置、被禁用或凭据锁定时的稳定失败类型。 */
public final class EmbeddingUnavailableException extends IllegalStateException {
    /**
     * 创建不携带 Secret 的失败。
     *
     * @param message 可展示的简短原因
     */
    public EmbeddingUnavailableException(String message) {
        super(message);
    }

    /**
     * 创建保留内部诊断原因但不改变公开错误类型的失败。
     *
     * @param message 可展示的简短原因
     * @param cause 内部失败
     */
    public EmbeddingUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
