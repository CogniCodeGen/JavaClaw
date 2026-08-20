package com.javaclaw.ui.javafx.plugin;

import com.javaclaw.application.plugin.PluginManagementApplicationService.Plugin;
import com.javaclaw.application.plugin.PluginManagementApplicationService.State;

/** 插件卡片与详情共用的稳定展示映射。 */
final class PluginUiText {

    private PluginUiText() {}

    static String glyph(Plugin plugin) {
        if (plugin.name().isBlank()) return "⧉";
        return new String(Character.toChars(plugin.name().codePointAt(0)));
    }

    static String state(State state) {
        return switch (state) {
            case ACTIVE -> "运行中";
            case FAILED -> "失败";
            case STOPPED -> "已停用";
            case LOADED -> "已加载";
            case DISCOVERED -> "已发现";
            case PENDING_APPROVAL -> "待批准";
        };
    }

    static String stateStyle(State state) {
        return switch (state) {
            case ACTIVE -> "jc-badge-running";
            case FAILED -> "jc-badge-failed";
            default -> "jc-badge-stopped";
        };
    }

    static String capabilitiesLine(Plugin plugin) {
        if (plugin.active() && !plugin.tools().isEmpty()) {
            return plugin.tools().size() + " 个工具";
        }
        return plugin.permissions().isEmpty()
                ? "无需授权" : plugin.permissions().size() + " 项能力";
    }

    static String metadata(Plugin plugin) {
        return "v" + plugin.version() + " · " + capabilitiesLine(plugin);
    }
}
