package com.javaclaw.framework.api;

/** User-task acceptance, independent of the technical {@link RunState}. */
public enum TaskOutcome {
    VERIFIED_COMPLETE,
    DELIVERED,
    PARTIAL,
    BLOCKED,
    UNVERIFIED,
    NOT_APPLICABLE
}
