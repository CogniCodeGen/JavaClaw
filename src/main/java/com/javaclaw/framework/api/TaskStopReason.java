package com.javaclaw.framework.api;

/** Host-authored reason code for a task Harness stop event. */
public enum TaskStopReason {
    MODEL_BLOCKED,
    BUDGET_EXHAUSTED,
    UNRELIABLE_CONTRACT,
    NO_PROGRESS,
    REPAIR_LIMIT
}
