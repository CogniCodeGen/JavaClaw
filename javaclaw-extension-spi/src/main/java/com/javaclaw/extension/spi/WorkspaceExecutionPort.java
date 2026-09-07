package com.javaclaw.extension.spi;

/**
 * 平台为单次 Coding 调用绑定的执行能力。
 *
 * <p>身份、操作、参数、权限和取消信号在平台进入 Handler 前固定。扩展不能替换参数，也不能将此能力保存给后续调用； 平台实现只接受一次调用，并在 Handler 返回后失效。普通扩展得到拒绝实现。
 */
@FunctionalInterface
public interface WorkspaceExecutionPort {
    /**
     * 执行当前已经绑定的唯一操作。
     *
     * @return 符合当前操作 Schema 的平台结果
     * @throws Exception 授权失效、重复调用、执行失败或结果不明确
     */
    ExtensionResponse invoke() throws Exception;

    /**
     * 创建不携带任何工作区执行权限的端口。
     *
     * @return 每次调用都拒绝的端口
     */
    static WorkspaceExecutionPort denied() {
        return () -> {
            throw new SecurityException("当前扩展调用没有 Workspace 执行能力");
        };
    }
}
