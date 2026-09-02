package com.javaclaw.api;

/** Thread 的当前生命周期状态。 */
public enum ThreadStatus {
    /** 可以继续创建 Turn。 */
    ACTIVE,
    /** 已归档但仍可读取。 */
    ARCHIVED,
    /** 正在等待人工输入或审批。 */
    WAITING,
    /** 因不可恢复错误停止。 */
    FAILED
}
