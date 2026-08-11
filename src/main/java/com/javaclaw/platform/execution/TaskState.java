package com.javaclaw.platform.execution;

/** 托管任务的单向生命周期状态。 */
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
