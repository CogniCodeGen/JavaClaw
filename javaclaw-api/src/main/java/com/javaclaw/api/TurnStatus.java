package com.javaclaw.api;

/** Turn 的状态；一次 Thread 同时最多有一个活动 Turn。 */
public enum TurnStatus {
    /** 已持久化但尚未调度。 */
    QUEUED,
    /** 正在执行。 */
    RUNNING,
    /** 等待审批或用户输入。 */
    WAITING,
    /** 正常完成。 */
    COMPLETED,
    /** 已响应取消请求。 */
    CANCELLED,
    /** 执行失败。 */
    FAILED
}
