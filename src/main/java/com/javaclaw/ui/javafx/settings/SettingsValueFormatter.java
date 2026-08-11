package com.javaclaw.ui.javafx.settings;

/** 设置页面共享的纯展示格式化。 */
final class SettingsValueFormatter {

    private SettingsValueFormatter() { }

    static String humanReadableBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024L * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024L * 1024 * 1024) {
            return String.format("%.1f MB", bytes / (1024.0 * 1024));
        }
        return String.format("%.1f GB", bytes / (1024.0 * 1024 * 1024));
    }
}
