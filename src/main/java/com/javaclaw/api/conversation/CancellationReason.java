package com.javaclaw.api.conversation;

/** 一次对话运行被取消的原因。 */
public enum CancellationReason {
    USER_REQUEST,
    MODE_SWITCH,
    SESSION_SWITCH,
    SCHEDULE_DISABLED,
    RUNTIME_REBUILD,
    SHUTDOWN
}
