package com.javaclaw.api;

/** 隔离进程执行边界；具体平台实现位于 Native Hosts。 */
public interface SandboxExecutor {
    /**
     * 执行批处理命令并等待终态。
     *
     * @param command 已验证命令
     * @param permission 最终有效权限
     * @param cancellation Turn 取消信号
     * @return 有界结果
     * @throws Exception 启动、隔离或等待失败
     */
    SandboxResult execute(SandboxCommand command, PermissionProfile permission, CancellationToken cancellation)
            throws Exception;

    /**
     * 打开交互会话；调用方负责关闭。
     *
     * @param command PTY 命令
     * @param permission 最终有效权限
     * @param cancellation Turn 取消信号
     * @return 已启动会话
     * @throws Exception 启动或隔离失败
     */
    SandboxSession open(SandboxCommand command, PermissionProfile permission, CancellationToken cancellation)
            throws Exception;
}
