package com.javaclaw.plugin.api.exec;

/** 插件任务只读生命周期状态。 */
public enum TaskState {
    QUEUED,
    RUNNING,
    SUCCEEDED,
    FAILED,
    CANCELLED;

    public boolean isTerminal() {
        return this == SUCCEEDED || this == FAILED || this == CANCELLED;
    }
}
