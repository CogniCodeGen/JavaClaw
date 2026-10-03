package com.javaclaw.application.schedule;

/** Stable execution status projected from persisted scheduler rows. */
public enum ScheduleExecutionStatus {
    SUCCESS("运行完成"), FAILURE("失败"), CANCELLED("已取消"), UNKNOWN("状态未知");

    private final String label;

    ScheduleExecutionStatus(String label) { this.label = label; }

    public String label() { return label; }

    /** Format 4 stores only stable status codes; labels are presentation only. */
    public static ScheduleExecutionStatus fromStored(String stored) {
        if (stored == null) return UNKNOWN;
        try { return valueOf(stored); }
        catch (IllegalArgumentException invalid) { return UNKNOWN; }
    }
}
