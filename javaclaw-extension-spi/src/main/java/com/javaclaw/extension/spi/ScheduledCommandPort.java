package com.javaclaw.extension.spi;

import com.javaclaw.api.CancellationToken;

/** Schedule 执行扩展显式可调度命令的受限平台端口。 */
@FunctionalInterface
public interface ScheduledCommandPort {
    /**
     * 创建始终安全拒绝的端口。
     *
     * @return 未装配调度命令路由的实现
     */
    static ScheduledCommandPort unavailable() {
        return (command, cancellation) -> {
            cancellation.throwIfCancelled();
            throw new IllegalStateException("Scheduled command port is unavailable");
        };
    }

    /**
     * 执行一个已由平台验证为可调度的扩展命令。
     *
     * @param command 冻结目标、参数、revision、Schema 与 Schedule 来源
     * @param cancellation 取消信号
     * @return 规范响应
     * @throws Exception 目标被禁用、撤权或执行失败
     */
    ExtensionResponse execute(ScheduledCommand command, CancellationToken cancellation) throws Exception;
}
