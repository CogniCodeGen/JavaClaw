package com.javaclaw.workflow.model;

public enum RunStatus {
    CREATED,
    RUNNING,
    WAITING_INPUT,
    PAUSED,
    RECOVERY_REQUIRED,
    COMPLETED,
    FAILED,
    CANCELLED;

    public boolean terminal() {
        return this == COMPLETED || this == FAILED || this == CANCELLED;
    }
}
