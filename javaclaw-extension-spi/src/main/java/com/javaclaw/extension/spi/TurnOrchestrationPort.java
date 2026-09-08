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

    /**
     * 执行机器整理等派生 Turn，平台须原子标记为不进入后续原始对话证据。
     *
     * @param command 冻结 Turn 输入
     * @param cancellation 父级取消
     * @return Turn 终态
     * @throws Exception 不支持来源标记或执行失败
     */
    default OrchestratedTurnResult executeDerived(OrchestratedTurnCommand command, CancellationToken cancellation)
            throws Exception {
        throw new UnsupportedOperationException("derived Turn provenance is unavailable");
    }
}
