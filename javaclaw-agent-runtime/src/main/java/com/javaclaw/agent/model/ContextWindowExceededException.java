package com.javaclaw.agent.model;

/** Provider 明确报告输入超过上下文窗口；仅压缩协调器可据此执行一次受限恢复。 */
public final class ContextWindowExceededException extends Exception {
    /** 使用不含请求正文的稳定错误创建恢复信号。 */
    public ContextWindowExceededException(String message) {
        super(message);
    }

    /** 保留 Provider 异常作为不可展示 cause，外层不得把其正文写入 Item。 */
    public ContextWindowExceededException(String message, Throwable cause) {
        super(message, cause);
    }
}
