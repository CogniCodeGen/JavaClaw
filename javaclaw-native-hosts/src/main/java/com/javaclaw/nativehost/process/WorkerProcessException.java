package com.javaclaw.nativehost.process;

/** 受监督 Worker 的启动、framing、超时或异常退出。 */
public final class WorkerProcessException extends RuntimeException {
    /** 创建脱敏失败。 */
    public WorkerProcessException(String message) {
        super(message);
    }

    /** 创建带底层原因的脱敏失败。 */
    public WorkerProcessException(String message, Throwable cause) {
        super(message, cause);
    }
}
