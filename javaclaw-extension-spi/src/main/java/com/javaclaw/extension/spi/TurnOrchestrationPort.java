package com.javaclaw.extension.spi;

import com.javaclaw.api.CancellationToken;

/** 扩展编排器启动单个 Turn 的唯一平台端口。 */
@FunctionalInterface
public interface TurnOrchestrationPort {
    /**
     * 启动并等待一个 Turn 终态；预算由平台从父级预留。
     *
     * @param command Turn 输入
     * @param cancellation 父级取消信号
     * @return Turn 结果
     * @throws Exception 启动或执行失败
     */
    OrchestratedTurnResult execute(OrchestratedTurnCommand command, CancellationToken cancellation) throws Exception;
}
