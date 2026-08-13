package com.javaclaw.workflow.model;

public enum RunStatus {
    CREATED,
    RUNNING,
    WAITING_INPUT,
    PAUSED,
    RECOVERY_REQUIRED,
    RECOVERY_BLOCKED_MISSING_EXTENSION,
    COMPLETED,
    FAILED,
    CANCELLED;

    public boolean terminal() {
        return this == COMPLETED || this == FAILED || this == CANCELLED;
    }
}
