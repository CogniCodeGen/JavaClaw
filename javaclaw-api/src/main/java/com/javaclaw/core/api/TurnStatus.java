package com.javaclaw.core.api;

/** Turn 的执行状态；审批与输入等待属于非终态，可在恢复流程中中断。 */
public enum TurnStatus {
    QUEUED,
    IN_PROGRESS,
    WAITING_FOR_APPROVAL,
    WAITING_FOR_INPUT,
    COMPLETED,
    INTERRUPTED,
    FAILED;

    /** 判断是否为 COMPLETED、INTERRUPTED 或 FAILED；这些状态不再继续执行。 */
    public boolean terminal() {
        return this == COMPLETED || this == INTERRUPTED || this == FAILED;
    }
}
