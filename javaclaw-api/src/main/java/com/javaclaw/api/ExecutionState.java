package com.javaclaw.api;

/** 可恢复后台执行的统一持久状态；领域扩展不得另造含义相同的运行状态。 */
public enum ExecutionState {
    /** 已持久化，等待 Supervisor 领取。 */
    QUEUED,
    /** 正在推进一个有界工作单元。 */
    RUNNING,
    /** 工作单元正在等待人工审批。 */
    WAITING_APPROVAL,
    /** 工作单元正在等待用户输入。 */
    WAITING_INPUT,
    /** 用户主动暂停，恢复前不得领取。 */
    PAUSED,
    /** 全部工作单元已经完成。 */
    COMPLETED,
    /** 执行失败，保留最后 checkpoint。 */
    FAILED,
    /** 用户取消，后续工作单元不得启动。 */
    CANCELLED;

    /**
     * 判断状态是否禁止继续推进。
     *
     * @return 完成、失败或取消时为 {@code true}
     */
    public boolean terminal() {
        return this == COMPLETED || this == FAILED || this == CANCELLED;
    }
}
