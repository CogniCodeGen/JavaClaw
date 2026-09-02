package com.javaclaw.runtime;

import com.javaclaw.api.CancellationToken;

/** 只执行单个 Turn 的 Thin Harness；不包含 Plan、Workflow 或其他业务状态机。 */
@FunctionalInterface
public interface TurnHarness {
    /**
     * 执行到终态。
     *
     * @param command Turn 输入
     * @param cancellation 取消信号
     * @return 已持久化的终态结果
     * @throws Exception 无法可靠持久化生命周期时抛出
     */
    TurnExecutionResult execute(TurnExecutionCommand command, CancellationToken cancellation) throws Exception;
}
