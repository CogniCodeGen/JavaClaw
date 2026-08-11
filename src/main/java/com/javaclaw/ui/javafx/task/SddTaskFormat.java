package com.javaclaw.ui.javafx.task;

import com.javaclaw.task.sdd.run.SddTaskState;

/** SDD 页面共享的纯格式化规则。 */
final class SddTaskFormat {
    private SddTaskFormat() {}

    static String badgeLabel(SddTaskState state) {
        return switch (state) {
            case RUNNING -> "● 运行中";
            case NEEDS_HUMAN -> "◐ 待人工";
            case COMPLETED -> "✓ 已完成";
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
