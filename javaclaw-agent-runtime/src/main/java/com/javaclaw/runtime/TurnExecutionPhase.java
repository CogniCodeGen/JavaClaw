package com.javaclaw.runtime;

/**
 * Turn Harness 的持久恢复相位。
 *
 * <p>{@link #MODEL_IN_FLIGHT} 与 {@link #TOOL_IN_FLIGHT} 表示外部调用意图已经提交、结果尚未提交。App Server
 * 硬崩溃后不能证明调用是否完成，必须失败关闭，禁止自动重放。审批等待和已决议阶段仍未进入外部工具，可以依据持久审批恢复。
 */
public enum TurnExecutionPhase {
    /** 可以安全发起下一次模型调用。 */
    READY_FOR_MODEL,
    /** 模型调用意图已提交，结果尚未提交。 */
    MODEL_IN_FLIGHT,
    /** 无工具调用的模型结果已提交，可以安全提交 Turn 终态。 */
    MODEL_COMMITTED,
    /** 模型工具调用批次已提交，可以执行下一个尚未完成的调用。 */
    TOOLS_READY,
    /** 工具意图已提交，但仍在等待持久审批，尚未调用外部工具。 */
    TOOL_WAITING_APPROVAL,
    /** 审批已持久决议，已扣减的同一工具意图可恢复。 */
    TOOL_APPROVAL_RESOLVED,
    /** 工具调用意图已提交，ToolResult 与 EffectReceipt 尚未提交。 */
    TOOL_IN_FLIGHT;

    /**
     * 返回该相位在进程重启后是否存在不明确的外部结果。
     *
     * @return 是否必须失败关闭
     */
    public boolean unknownAfterRestart() {
        return this == MODEL_IN_FLIGHT || this == TOOL_IN_FLIGHT;
    }

    /**
     * 返回该相位是否属于已提交的工具批次。
     *
     * @return 是否必须持有待执行工具批次
     */
    public boolean toolBatchPhase() {
        return this == TOOLS_READY
                || this == TOOL_WAITING_APPROVAL
                || this == TOOL_APPROVAL_RESOLVED
                || this == TOOL_IN_FLIGHT;
    }

    /**
     * 返回该相位是否必须保留已提交意图摘要。
     *
     * @return 是否需要 intent digest
     */
    public boolean requiresIntentDigest() {
        return this == MODEL_IN_FLIGHT
                || this == TOOL_WAITING_APPROVAL
                || this == TOOL_APPROVAL_RESOLVED
                || this == TOOL_IN_FLIGHT;
    }
}
