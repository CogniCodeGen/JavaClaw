package com.javaclaw.api.conversation;

/** 一次对话运行被取消的原因。 */
public enum CancellationReason {
    USER_REQUEST,
    APPROVAL_DENIED,
    UNKNOWN,
    MODE_SWITCH,
    SESSION_SWITCH,
    SCHEDULE_DISABLED,
    RUNTIME_REBUILD,
    SHUTDOWN,
    /** The Run exceeded its wall-clock deadline; this is not a user stop. */
    RUN_TIMEOUT,
    /** A later user request replaced this task. */
    TASK_SUPERSEDED
}
