package com.javaclaw.ui.javafx.mcp;

import com.javaclaw.application.mcp.McpManagementApplicationService.Server;
import com.javaclaw.application.mcp.McpManagementApplicationService.State;
import com.javaclaw.application.mcp.McpManagementApplicationService.Transport;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.stream.Collectors;

/** 单个 MCP 服务器卡片的纯展示映射。 */
final class McpServerCardViewModel {

    private Server server;

    void apply(Server value) { server = java.util.Objects.requireNonNull(value, "value"); }
    Server server() { return server; }

    String badgeText() {
        return switch (server.state()) {
            case RUNNING -> "● 运行中";
            case STARTING -> "◐ 启动中";
            case FAILED -> "● 启动失败";
            case STOPPED -> server.enabled() ? "○ 已停止" : "○ 已禁用";
        };
    }

    String badgeClass() {
        return "jc-badge-" + server.state().name().toLowerCase(java.util.Locale.ROOT);
    }

    String metaText() {
        if (server.state() != State.RUNNING) return "";
        String uptime = humanizeUptime(server.startedAtMs());
        return "· " + server.tools().size() + " 个工具" + (uptime.isEmpty() ? "" : " · " + uptime);
    }

    String summaryText() {
        return (server.transport() == Transport.HTTP ? "HTTP: " : "命令: ") + server.launchSummary();
    }

    String valuesText() {
        Map<String, String> values = server.transport() == Transport.HTTP
                ? server.headers() : server.environment();
        if (values.isEmpty()) return "";
        String prefix = server.transport() == Transport.HTTP ? "Headers: " : "环境变量: ";
        return prefix + values.entrySet().stream().map(entry -> entry.getKey() + "="
                + mask(entry.getValue())).collect(Collectors.joining(", "));
    }

    String toolsTitle() {
        return switch (server.state()) {
            case RUNNING -> server.tools().isEmpty() ? "可用工具（0 个）"
                    : "已发现 " + server.tools().size() + " 个工具";
            case STARTING -> "可用工具（启动中…）";
            case FAILED -> "可用工具（启动失败）";
            case STOPPED -> "可用工具（启动后发现）";
        };
    }

    String toolsHint() {
        return switch (server.state()) {
            case RUNNING -> "该服务器已连接，但未声明任何工具。";
            case STARTING -> "正在启动并发现工具，稍候自动刷新…";
            case FAILED -> "服务器启动失败，请先排查错误。";
            case STOPPED -> "当前未启动。启动后即可查看该服务器提供的工具列表。";
        };
    }

    private static String humanizeUptime(long startedAtMs) {
        if (startedAtMs <= 0) return "";
        long seconds = Math.max(0, Duration.between(
                Instant.ofEpochMilli(startedAtMs), Instant.now()).getSeconds());
        if (seconds < 60) return "运行 " + seconds + "s";
        if (seconds < 3600) return "运行 " + seconds / 60 + "m";
        if (seconds < 86400) return "运行 " + seconds / 3600 + "h";
        return "运行 " + seconds / 86400 + "d";
    }

    private static String mask(String value) {
        if (value == null || value.length() <= 8) return value == null ? "" : value;
        return value.substring(0, 4) + "****" + value.substring(value.length() - 4);
    }
}
