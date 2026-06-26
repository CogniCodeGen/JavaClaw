package com.javaclaw.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * MCP 客户端管理器 — 管理多个 MCP 服务器的生命周期
 *
 * <p>负责启动/停止所有启用的 MCP 服务器，提供统一的工具发现和调用接口。</p>
 *
 * @author JavaClaw
 */
public class McpClientManager {

    private static final Logger log = LoggerFactory.getLogger(McpClientManager.class);

    /** 活跃的客户端（服务器名称 → 客户端） */
    private final Map<String, McpClient> clients = new ConcurrentHashMap<>();

    /** 状态变化监听器；任意 client 状态变化或 client 增删时触发 */
    private final List<Runnable> stateListeners = new CopyOnWriteArrayList<>();

    /**
     * 启动所有启用的 MCP 服务器
     */
    public void startAll() {
        List<McpServerConfig> enabledServers = McpConfigManager.getInstance().getEnabledServers();
        if (enabledServers.isEmpty()) {
            log.info("没有启用的 MCP 服务器");
            return;
        }

        log.info("正在启动 {} 个 MCP 服务器...", enabledServers.size());
        for (McpServerConfig config : enabledServers) {
            startServer(config);
        }
    }

    /**
     * 启动单个 MCP 服务器
     */
    public boolean startServer(McpServerConfig config) {
        // 如果已有同名客户端在运行，先停止
        McpClient existing = clients.get(config.getName());
        if (existing != null && existing.isRunning()) {
            existing.stop();
        }

        McpClient client = new McpClient(config);
        // 把状态变化广播给 UI 监听器
        client.setStateChangeListener(this::notifyStateListeners);
        // 即便启动失败，也保留 client 实例，供 UI 展示 FAILED 状态和错误信息
        clients.put(config.getName(), client);
        try {
            client.start();
            log.info("MCP 服务器 {} 已启动，发现 {} 个工具",
                    config.getName(), client.getTools().size());
            notifyStateListeners();
            return true;
        } catch (Exception e) {
            log.error("启动 MCP 服务器 {} 失败: {}", config.getName(), e.getMessage(), e);
            client.stop();
            notifyStateListeners();
            return false;
        }
    }

    /**
     * 停止单个 MCP 服务器
     */
    public void stopServer(String name) {
        McpClient client = clients.remove(name);
        if (client != null) {
            client.stop();
            notifyStateListeners();
        }
    }

    /**
     * 同步重启服务器：停止现有连接 → 重新启动。
     * 用户在卡片上点击「重启」时调用。
     */
    public boolean restartServer(McpServerConfig config) {
        stopServer(config.getName());
        return startServer(config);
    }

    /**
     * 一次性测试连接：临时启动 → 发现工具 → 立即关闭。
     * 不影响正在运行的同名服务器（独立 McpClient 实例）。
     *
     * @return 测试结果（成功/失败、工具列表、耗时、错误摘要）
     */
    public TestResult testConnection(McpServerConfig config) {
        long t0 = System.currentTimeMillis();
        McpClient temp = new McpClient(config);
        try {
            temp.start();
            List<McpClient.McpToolInfo> tools = List.copyOf(temp.getTools());
            long elapsed = System.currentTimeMillis() - t0;
            return new TestResult(true, tools, elapsed, null,
                    temp.getServerName(), temp.getServerVersion());
        } catch (Exception e) {
            long elapsed = System.currentTimeMillis() - t0;
            String err = temp.getStartupError() != null ? temp.getStartupError() : e.getMessage();
            return new TestResult(false, List.of(), elapsed, err, null, null);
        } finally {
            try { temp.stop(); } catch (Exception ignored) {}
        }
    }

    /**
     * 测试连接结果（不可变）。UI 侧据此渲染成功/失败提示。
     */
    public record TestResult(
            boolean success,
            List<McpClient.McpToolInfo> tools,
            long elapsedMs,
            String errorMessage,
            String serverName,
            String serverVersion) {}

    /**
     * 停止所有 MCP 服务器
     */
    public void stopAll() {
        log.info("正在停止所有 MCP 服务器...");
        for (McpClient client : clients.values()) {
            try {
                client.stop();
            } catch (Exception e) {
                log.warn("停止 MCP 服务器 {} 时出错", client.getConfig().getName(), e);
            }
        }
        clients.clear();
        log.info("所有 MCP 服务器已停止");
    }

    /**
     * 调用指定服务器上的工具
     *
     * @param serverName 服务器名称
     * @param toolName   工具名称
     * @param arguments  参数（JSON 对象）
     * @return 工具返回的内容文本
     */
    public String callTool(String serverName, String toolName, JsonNode arguments) throws Exception {
        McpClient client = clients.get(serverName);
        if (client == null || !client.isRunning()) {
            throw new RuntimeException("MCP 服务器 '" + serverName + "' 未运行");
        }
        return client.callTool(toolName, arguments);
    }

    /**
     * 获取所有活跃服务器上的工具列表
     *
     * @return 服务器名称 → 工具列表
     */
    public Map<String, List<McpClient.McpToolInfo>> getAllTools() {
        Map<String, List<McpClient.McpToolInfo>> result = new LinkedHashMap<>();
        for (Map.Entry<String, McpClient> entry : clients.entrySet()) {
            if (entry.getValue().isRunning()) {
                result.put(entry.getKey(), entry.getValue().getTools());
            }
        }
        return result;
    }

    /**
     * 获取所有活跃的 MCP 客户端
     */
    public Collection<McpClient> getActiveClients() {
        return clients.values().stream()
                .filter(McpClient::isRunning)
                .toList();
    }

    /**
     * 获取指定服务器的客户端
     */
    public McpClient getClient(String serverName) {
        return clients.get(serverName);
    }

    /**
     * 是否有活跃的 MCP 服务器
     */
    public boolean hasActiveServers() {
        return clients.values().stream().anyMatch(McpClient::isRunning);
    }

    /**
     * 获取指定服务器的状态快照（包括 FAILED / STOPPED 服务器）。
     * 服务器从未 startServer 过则返回 STOPPED 默认状态。
     */
    public ServerStatus getServerStatus(String name) {
        McpClient client = clients.get(name);
        if (client == null) {
            return new ServerStatus(McpClient.ServerState.STOPPED, 0, null, 0L);
        }
        return new ServerStatus(
                client.getState(),
                client.getTools().size(),
                client.getStartupError(),
                client.getStartedAtMs());
    }

    /** 获取指定服务器的 stderr 最近输出（用于错误抽屉） */
    public List<String> getStderrTail(String name) {
        McpClient client = clients.get(name);
        return client != null ? client.getStderrTail() : List.of();
    }

    /**
     * 添加状态变化监听器（任意客户端状态变化、增删时触发）。
     * UI 用于自动刷新卡片，避免轮询。
     */
    public void addStateListener(Runnable listener) {
        if (listener != null) stateListeners.add(listener);
    }

    public void removeStateListener(Runnable listener) {
        stateListeners.remove(listener);
    }

    private void notifyStateListeners() {
        for (Runnable r : stateListeners) {
            try { r.run(); } catch (Exception e) {
                log.debug("MCP 状态监听器触发失败", e);
            }
        }
    }

    /**
     * 服务器状态快照。
     */
    public record ServerStatus(
            McpClient.ServerState state,
            int toolCount,
            String startupError,
            long startedAtMs) {}

    /**
     * 构建 MCP 工具描述（用于注入到系统提示词）
     *
     * @return MCP 工具的描述文本，为空时返回空字符串
     */
    public String buildToolsPrompt() {
        Map<String, List<McpClient.McpToolInfo>> allTools = getAllTools();
        if (allTools.isEmpty()) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("\n\n## MCP 外部工具\n\n");
        sb.append("以下工具来自已连接的 MCP 服务器，可通过 mcp_call_tool 工具调用：\n\n");

        for (Map.Entry<String, List<McpClient.McpToolInfo>> entry : allTools.entrySet()) {
            String serverName = entry.getKey();
            List<McpClient.McpToolInfo> tools = entry.getValue();
            sb.append("### 服务器: ").append(serverName).append("\n\n");

            for (McpClient.McpToolInfo tool : tools) {
                sb.append("- **").append(tool.getName()).append("**");
                if (tool.getDescription() != null && !tool.getDescription().isEmpty()) {
                    sb.append(" — ").append(tool.getDescription());
                }
                // 输出参数 schema 摘要
                if (tool.getInputSchema() != null && tool.getInputSchema().has("properties")) {
                    sb.append("\n  参数: ");
                    JsonNode props = tool.getInputSchema().get("properties");
                    Iterator<String> fieldNames = props.fieldNames();
                    List<String> paramDescs = new ArrayList<>();
                    while (fieldNames.hasNext()) {
                        String fieldName = fieldNames.next();
                        JsonNode prop = props.get(fieldName);
                        String type = prop.has("type") ? prop.get("type").asText() : "any";
                        String desc = prop.has("description") ? prop.get("description").asText() : "";
                        paramDescs.add(fieldName + " (" + type + ")" + (desc.isEmpty() ? "" : ": " + desc));
                    }
                    sb.append(String.join(", ", paramDescs));
                }
                sb.append("\n");
            }
            sb.append("\n");
        }

        sb.append("调用方式：使用 mcp_call_tool 工具，指定 server_name、tool_name 和 arguments_json 参数。\n");
        return sb.toString();
    }

    /**
     * 构建指定 MCP 服务器的工具描述提示词（路由筛选后使用）
     *
     * @param serverNames 需要包含的服务器名列表，包含 "all" 时返回全部
     * @return 筛选后的 MCP 工具描述，为空时返回空字符串
     */
    public String buildFilteredToolsPrompt(List<String> serverNames) {
        if (serverNames == null || serverNames.isEmpty()) {
            return "";
        }
        if (serverNames.contains("all")) {
            return buildToolsPrompt();
        }

        Map<String, List<McpClient.McpToolInfo>> allTools = getAllTools();
        if (allTools.isEmpty()) {
            return "";
        }

        // 只包含指定服务器的工具描述
        StringBuilder sb = new StringBuilder();
        sb.append("\n\n## MCP 外部工具\n\n");
        sb.append("以下工具来自已连接的 MCP 服务器，可通过 mcp_call_tool 工具调用：\n\n");

        boolean hasContent = false;
        for (String serverName : serverNames) {
            List<McpClient.McpToolInfo> tools = allTools.get(serverName);
            if (tools == null || tools.isEmpty()) continue;
            hasContent = true;

            sb.append("### 服务器: ").append(serverName).append("\n\n");
            for (McpClient.McpToolInfo tool : tools) {
                sb.append("- **").append(tool.getName()).append("**");
                if (tool.getDescription() != null && !tool.getDescription().isEmpty()) {
                    sb.append(" — ").append(tool.getDescription());
                }
                if (tool.getInputSchema() != null && tool.getInputSchema().has("properties")) {
                    sb.append("\n  参数: ");
                    JsonNode props = tool.getInputSchema().get("properties");
                    Iterator<String> fieldNames = props.fieldNames();
                    List<String> paramDescs = new ArrayList<>();
                    while (fieldNames.hasNext()) {
                        String fieldName = fieldNames.next();
                        JsonNode prop = props.get(fieldName);
                        String type = prop.has("type") ? prop.get("type").asText() : "any";
                        String desc = prop.has("description") ? prop.get("description").asText() : "";
                        paramDescs.add(fieldName + " (" + type + ")" + (desc.isEmpty() ? "" : ": " + desc));
                    }
                    sb.append(String.join(", ", paramDescs));
                }
                sb.append("\n");
            }
            sb.append("\n");
        }

        if (!hasContent) {
            return "";
        }

        sb.append("调用方式：使用 mcp_call_tool 工具，指定 server_name、tool_name 和 arguments_json 参数。\n");
        return sb.toString();
    }
}
