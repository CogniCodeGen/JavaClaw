package com.javaclaw.framework.api;

/** Outcome of invoking a tool, independent of whether its intended effect was verified. */
public enum ToolExecutionStatus {
    SUCCEEDED,
    FAILED,
    TIMED_OUT,
    PENDING,
    UNCERTAIN,
    REOBSERVE,
    UNKNOWN;

    public boolean succeeded() {
        return this == SUCCEEDED;
    }

    public boolean needsReconciliation() {
        return this == UNCERTAIN || this == TIMED_OUT || this == PENDING;
    }

    public static ToolExecutionStatus fromCode(String value) {
        if (value == null || value.isBlank()) return UNKNOWN;
        try { return valueOf(value); }
        catch (IllegalArgumentException invalid) { return UNKNOWN; }
    }
}
