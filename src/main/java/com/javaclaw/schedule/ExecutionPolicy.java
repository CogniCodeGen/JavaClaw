package com.javaclaw.schedule;

/** Controls whether a repeating schedule keeps firing after a verified task result. */
public enum ExecutionPolicy {
    /** Run at every configured trigger until a user disables the schedule. */
    RECURRING,
    /** Disable after a structured task result confirms the target is complete. */
    UNTIL_CONDITION
}
