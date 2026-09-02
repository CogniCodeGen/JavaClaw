package com.javaclaw.browser.client;

/** Browser Worker 启动、协议、超时或执行失败。 */
public final class BrowserWorkerException extends RuntimeException {
    /**
     * 创建失败。
     *
     * @param message 脱敏诊断
     */
    public BrowserWorkerException(String message) {
        super(message);
    }

    /**
     * 创建带原因的失败。
     *
     * @param message 脱敏诊断
     * @param cause 底层原因
     */
    public BrowserWorkerException(String message, Throwable cause) {
        super(message, cause);
    }
}
