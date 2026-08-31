package com.javaclaw.sandbox.api;

/** Sole public entry point for agent-controlled process execution. */
@FunctionalInterface
public interface SandboxExecutor {
    /**
     * 在可精确实施 command.policy 的独立沙箱中执行命令，无法实施时必须失败关闭。
     *
     * @return 有界输出、退出码和终止原因
     * @throws Exception 平台能力不足、策略拒绝、启动失败或执行被取消
     */
    SandboxResult execute(SandboxCommand command) throws Exception;

    /** Opens a launcher-owned process session. Implementations without exact support fail closed. */
    default SandboxSession openSession(SandboxCommand command, SandboxSessionOptions options) throws Exception {
        throw new UnsupportedOperationException("sandbox sessions are unavailable");
    }
}
