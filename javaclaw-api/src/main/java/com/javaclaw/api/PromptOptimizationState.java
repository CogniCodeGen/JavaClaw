package com.javaclaw.api;

/** Prompt 优化任务从权威 Turn 状态投影出的只读状态。 */
public enum PromptOptimizationState {
    /** Turn 已持久化，等待 Harness 调度。 */
    QUEUED,
    /** Harness 正在执行，或等待受治理的审批/输入。 */
    RUNNING,
    /** Turn 已完成且存在可供人工采纳的草稿正文。 */
    READY,
    /** Turn 失败，或终态输出不能形成安全草稿。 */
    FAILED,
    /** 用户或平台已经取消 Turn。 */
    CANCELLED
}
