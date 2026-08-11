package com.javaclaw.ui.javafx.memory;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/** 记忆中心纯展示文本格式化；无 JavaFX 或业务依赖。 */
final class MemoryUiText {

    private static final DateTimeFormatter TIME =
            DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(ZoneId.systemDefault());

    private MemoryUiText() {}

    static String formatTime(long epochMillis) {
        return epochMillis <= 0 ? "" : TIME.format(Instant.ofEpochMilli(epochMillis));
    }

    static String oneLine(String value, int max) {
        if (value == null) return "";
        String normalized = value.replaceAll("\\s+", " ").strip();
        return normalized.length() > max ? normalized.substring(0, max) + "…" : normalized;
    }

    static String humanSize(long characters) {
        long bytes = Math.max(0, characters) * 2;
        if (bytes < 1024) return bytes + " B";
        double kibibytes = bytes / 1024.0;
        if (kibibytes < 1024) return String.format("%.1f KB", kibibytes);
        return String.format("%.1f MB", kibibytes / 1024);
    }

    static boolean matches(String query, String... values) {
        String normalized = query == null ? "" : query.strip().toLowerCase();
        if (normalized.isEmpty()) return true;
        for (String value : values) {
            if (value != null && value.toLowerCase().contains(normalized)) return true;
        }
        return false;
    }
}
