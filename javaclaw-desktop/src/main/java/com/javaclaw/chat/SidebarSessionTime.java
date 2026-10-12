package com.javaclaw.chat;

import java.time.LocalDate;
import java.time.LocalDateTime;

/** 会话列表的日期分组与短时间标签；参考日期由调用方提供。 */
final class SidebarSessionTime {
    private SidebarSessionTime() { }

    static String group(LocalDateTime createdAt, LocalDate today) {
        LocalDate date = createdAt == null ? today : createdAt.toLocalDate();
        if (date.equals(today)) return "今天";
        if (date.equals(today.minusDays(1))) return "昨天";
        if (date.isAfter(today.minusDays(7))) return "本周";
        return "更早";
    }

    static String label(LocalDateTime createdAt, LocalDate today) {
        if (createdAt == null) return "";
        LocalDate date = createdAt.toLocalDate();
        if (date.equals(today)) return String.format("%02d:%02d", createdAt.getHour(), createdAt.getMinute());
        if (date.equals(today.minusDays(1))) return "昨天";
        if (date.isAfter(today.minusDays(7))) {
            return switch (date.getDayOfWeek()) {
                case MONDAY -> "周一";
                case TUESDAY -> "周二";
                case WEDNESDAY -> "周三";
                case THURSDAY -> "周四";
                case FRIDAY -> "周五";
                case SATURDAY -> "周六";
                case SUNDAY -> "周日";
            };
        }
        return String.format("%02d-%02d", date.getMonthValue(), date.getDayOfMonth());
    }
}
