package com.javaclaw.agent.tool;

/** 沙箱启动、协议或执行失败；不能通过捕获此异常自动退化为无沙箱执行。 */
public final class SandboxExecutionException extends Exception {
    /** 保存可展示的失败说明；message 必须由调用方预先脱敏。 */
    public SandboxExecutionException(String message) {
        super(message);
    }

    /** 关联底层失败以保留诊断因果；向客户端映射时不得直接泄露 cause 内容。 */
    public SandboxExecutionException(String message, Throwable cause) {
        super(message, cause);
    }
}
