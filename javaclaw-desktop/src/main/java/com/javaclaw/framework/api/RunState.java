package com.javaclaw.framework.api;

/** Persisted Agent run state. Only AgentEngine may advance it. */
public enum RunState {
    CREATED,
    RUNNING,
    WAITING_INPUT,
    WAITING_APPROVAL,
    WAITING_CHILD,
    WAITING_EVENT,
    PAUSED,
    RECOVERY_BLOCKED_MISSING_EXTENSION,
    COMPLETED,
    FAILED,
    CANCELLED;

    public boolean terminal() {
        return this == COMPLETED || this == FAILED || this == CANCELLED;
    }
}
