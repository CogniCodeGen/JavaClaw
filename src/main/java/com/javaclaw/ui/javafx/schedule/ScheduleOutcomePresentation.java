package com.javaclaw.ui.javafx.schedule;

import com.javaclaw.application.schedule.ScheduleExecutionStatus;
import com.javaclaw.framework.api.TaskResult;

/** Keeps scheduler lifecycle labels separate from acceptance of the prompted task. */
final class ScheduleOutcomePresentation {
    private ScheduleOutcomePresentation() {}

    static String label(ScheduleExecutionStatus executionStatus, TaskResult taskResult) {
        if (executionStatus == ScheduleExecutionStatus.CANCELLED && taskResult != null
                && taskResult.outcome() == com.javaclaw.framework.api.TaskOutcome.BLOCKED) {
            return "已取消 · 任务受阻";
        }
        if (executionStatus != ScheduleExecutionStatus.SUCCESS) {
            return executionStatus.label();
        }
        if (taskResult == null) return "运行完成 · 历史未核验";
        return switch (taskResult.outcome()) {
            case VERIFIED_COMPLETE -> "任务已完成";
            case DELIVERED -> "答复已交付";
            case PARTIAL -> "运行完成 · 部分完成";
            case BLOCKED -> "运行完成 · 任务受阻";
            case UNVERIFIED -> "运行完成 · 未验证";
            case NOT_APPLICABLE -> "运行完成 · 无需验收";
        };
    }

    static boolean runCompleted(ScheduleExecutionStatus executionStatus) {
        return executionStatus == ScheduleExecutionStatus.SUCCESS;
    }
}
