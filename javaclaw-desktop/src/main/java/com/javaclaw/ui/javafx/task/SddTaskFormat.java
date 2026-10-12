package com.javaclaw.ui.javafx.task;

import com.javaclaw.task.sdd.run.SddTaskState;
import com.javaclaw.framework.api.TaskResult;

/** SDD 页面共享的纯格式化规则。 */
final class SddTaskFormat {
    private SddTaskFormat() {}

    static String badgeLabel(SddTaskState state) {
        return badgeLabel(state, null);
    }

    static String badgeLabel(SddTaskState state, TaskResult result) {
        return switch (state) {
            case RUNNING -> "● 运行中";
            case NEEDS_HUMAN -> "◐ 待人工";
            case COMPLETED -> result == null ? "✓ 编排结束 · 历史未核验"
                    : switch (result.outcome()) {
                        case VERIFIED_COMPLETE -> "✓ 任务已完成";
                        case DELIVERED -> "✓ 答复已交付";
                        case PARTIAL -> "◐ 编排结束 · 部分完成";
                        case BLOCKED -> "◐ 编排结束 · 任务受阻";
                        case UNVERIFIED -> "✓ 编排结束 · 未验证";
                        case NOT_APPLICABLE -> "✓ 编排结束 · 无需验收";
                    };
            case PAUSED -> "○ 已暂停";
            case FAILED -> "● 失败";
            case PENDING -> "○ 待启动";
            case CANCELLED -> "○ 已取消";
        };
    }

    static String badgeStyle(SddTaskState state) {
        return switch (state) {
            case RUNNING -> "jc-badge-running";
            case NEEDS_HUMAN -> "jc-badge-amber";
            case COMPLETED -> "jc-badge-soft";
            case PAUSED, PENDING, CANCELLED -> "jc-badge-stopped";
            case FAILED -> "jc-badge-failed";
        };
    }

    static String taskOutcomeLabel(TaskResult result) {
        if (result == null) return "历史记录未核验";
        return switch (result.outcome()) {
            case VERIFIED_COMPLETE -> "已核验完成";
            case DELIVERED -> "已交付";
            case PARTIAL -> "部分完成";
            case BLOCKED -> "受阻";
            case UNVERIFIED -> "未验证";
            case NOT_APPLICABLE -> "无需验收";
        };
    }

    static String tokens(long value) {
        if (value < 1_000) return String.valueOf(value);
        if (value < 1_000_000) return decimal(value / 1_000.0) + "K";
        return decimal(value / 1_000_000.0) + "M";
    }

    private static String decimal(double value) {
        String text = String.format("%.1f", value);
        return text.endsWith(".0") ? text.substring(0, text.length() - 2) : text;
    }
}
