package com.javaclaw.mcp;

import com.javaclaw.agent.ToolCallOrigin;
import com.javaclaw.agent.ToolConfirmationManager;
import com.javaclaw.agent.model.ToolResponse;
import com.javaclaw.api.interaction.SecretRequest;
import com.javaclaw.api.interaction.UserInteractionPort;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.javaclaw.util.ProjectAccessPolicy;
import com.javaclaw.util.SensitiveDataRedactor;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** 通过对话直接创建、更新、启停和删除 MCP Server 配置。 */
public final class McpManageTools {

    private static final Logger log = LoggerFactory.getLogger(McpManageTools.class);

    interface ServerStore {
        List<McpServerConfig> all();
        McpServerConfig get(String name);
        McpServerConfig save(McpServerConfig config);
        boolean delete(String name);
    }

    interface ServerRuntime {
        boolean start(McpServerConfig config);
        boolean restart(McpServerConfig config);
        void stop(String name);
        McpClientManager.ServerStatus status(String name);
    }

    private final ToolCallOrigin origin;
    private final ServerStore store;
    private final ServerRuntime runtime;

    public McpManageTools(McpClientManager clientManager, ToolCallOrigin origin) {
        this(origin, liveStore(), liveRuntime(Objects.requireNonNull(clientManager, "clientManager")));
    }

    McpManageTools(ToolCallOrigin origin, ServerStore store, ServerRuntime runtime) {
        this.origin = origin == null ? ToolCallOrigin.UNKNOWN : origin;
        this.store = Objects.requireNonNull(store, "store");
        this.runtime = Objects.requireNonNull(runtime, "runtime");
    }

    @Tool(name = "mcp_server_list",
            description = "列出当前工作区配置的全部 MCP Server、传输方式、启用/运行状态和工具数量。不会返回环境变量或 Header 的值。")
    public String listServers() {
        List<McpServerConfig> servers = store.all();
        if (servers.isEmpty()) {
            return ToolResponse.success("mcp_server_list", "当前工作区尚未配置 MCP Server。");
        }
        StringBuilder out = new StringBuilder("共 ").append(servers.size()).append(" 个 MCP Server：\n");
        for (McpServerConfig config : servers) {
            McpClientManager.ServerStatus status = runtime.status(config.getName());
            out.append("· ").append(config.getName())
                    .append(" — ").append(config.getTransport())
                    .append(config.isEnabled() ? "，已启用" : "，已停用")
                    .append("，状态 ").append(status.state())
                    .append("，工具 ").append(status.toolCount()).append(" 个");
            if ("http".equals(config.getTransport())) {
                out.append("，URL ").append(ProjectAccessPolicy.remoteEndpointSummary(config.getUrl()));
                if (!config.getHeaders().isEmpty()) {
                    out.append("，Header keys=").append(config.getHeaders().keySet());
                }
            } else {
                out.append("，command ").append(config.getCommand());
                if (!config.getEnv().isEmpty()) {
                    out.append("，env keys=").append(config.getEnv().keySet());
                }
            }
            out.append('\n');
        }
        return ToolResponse.success("mcp_server_list", out.toString().trim());
    }

    @Tool(name = "mcp_server_add",
            description = "直接新增并持久化一个 MCP Server 配置；已启用时立即启动并发现工具。"
                    + "config_json 支持 stdio（command/args/env）或 HTTP（url）；对话参数禁止 Header 值，"
                    + "鉴权请在创建后调用 mcp_server_set_header_secure。示例："
                    + "{\"command\":\"npx\",\"args\":[\"-y\",\"@pkg/server\"]}。"
                    + "服务器名称已存在时不会覆盖，请改用 mcp_server_update。配置可能启动本地程序或连接外部服务，需用户确认。")
    public String addServer(
            @ToolParam(name = "name", description = "服务器唯一名称") String name,
            @ToolParam(name = "config_json", description = "单个 MCP Server 的 JSON 配置，支持 command/args/env 或 url/headers") String configJson,
            @ToolParam(name = "enabled", description = "是否启用；省略时采用 JSON 值，JSON 也未设置时默认 true", required = false) Boolean enabled) {
        String serverName = strip(name);
        if (serverName.isEmpty()) {
            return ToolResponse.error("mcp_server_add", "name 不能为空。");
        }
        if (store.get(serverName) != null) {
            return ToolResponse.error("mcp_server_add",
                    "MCP Server「" + serverName + "」已存在；如需修改请用 mcp_server_update。");
        }
        return saveFromJson("mcp_server_add", serverName, configJson, enabled, false);
    }

    @Tool(name = "mcp_server_update",
            description = "更新已有 MCP Server 配置并持久化；启用时热重启使配置立即生效。"
                    + "config_json 形态与 mcp_server_add 相同且不得包含 Header 值。不会静默创建不存在的服务器。")
    public String updateServer(
            @ToolParam(name = "name", description = "已有服务器名称") String name,
            @ToolParam(name = "config_json", description = "完整的新 JSON 配置，支持 command/args/env 或 url/headers") String configJson,
            @ToolParam(name = "enabled", description = "是否启用；省略时采用 JSON 值", required = false) Boolean enabled) {
        String serverName = strip(name);
        if (store.get(serverName) == null) {
            return ToolResponse.error("mcp_server_update", "未找到 MCP Server: " + serverName);
        }
        return saveFromJson("mcp_server_update", serverName, configJson, enabled, true);
    }

    @Tool(name = "mcp_server_set_enabled",
            description = "启用或停用一个已配置的 MCP Server。启用会立即启动/发现工具，停用会停止当前连接。")
    public String setEnabled(
            @ToolParam(name = "name", description = "服务器名称") String name,
            @ToolParam(name = "enabled", description = "true=启用并启动，false=停用并停止") boolean enabled) {
        String serverName = strip(name);
        McpServerConfig existing = store.get(serverName);
        if (existing == null) {
            return ToolResponse.error("mcp_server_set_enabled", "未找到 MCP Server: " + serverName);
        }
        if (existing.isEnabled() == enabled) {
            return ToolResponse.success("mcp_server_set_enabled",
                    "MCP Server「" + serverName + "」已经" + (enabled ? "启用" : "停用") + "。");
        }
        String operation = enabled ? "启用并启动" : "停用并停止";
        if (!ToolConfirmationManager.requestConfirmation(origin,
                "mcp_server_set_enabled", operation + " MCP Server「" + serverName + "」")) {
            return ToolResponse.error("mcp_server_set_enabled",
                    "用户取消了" + (enabled ? "启用" : "停用") + "。");
        }
        McpServerConfig candidate = copyOf(existing);
        candidate.setEnabled(enabled);
        try {
            store.save(candidate);
            if (enabled) {
                boolean started = runtime.start(candidate);
                return started
                        ? ToolResponse.success("mcp_server_set_enabled", "已启用并启动 MCP Server「" + serverName + "」。")
                        : ToolResponse.success("mcp_server_set_enabled",
                                "配置已启用并确认写入数据库，但服务器启动失败：" + startupSummary(serverName));
            }
            runtime.stop(serverName);
            return ToolResponse.success("mcp_server_set_enabled", "已停用并停止 MCP Server「" + serverName + "」。");
        } catch (RuntimeException e) {
            log.error("切换 MCP Server 启用状态失败: {}", serverName, e);
            return ToolResponse.error("mcp_server_set_enabled", e.getMessage());
        }
    }

    @Tool(name = "mcp_server_set_header_secure",
            description = "为已有 HTTP MCP Server 设置一个 Header。系统弹出本地安全输入框读取 Header 值；"
                    + "值不会进入模型消息、工具参数、日志或回复，并只加密保存到项目配置数据库。")
    public String setHeaderSecure(
            @ToolParam(name = "name", description = "已有 HTTP MCP Server 名称") String name,
            @ToolParam(name = "header_name", description = "Header 名称，例如 Authorization 或 X-API-Key")
            String headerName) {
        String serverName = strip(name);
        String safeHeaderName = strip(headerName);
        McpServerConfig existing = store.get(serverName);
        if (existing == null) {
            return ToolResponse.error("mcp_server_set_header_secure", "未找到 MCP Server: " + serverName);
        }
        if (!"http".equals(existing.getTransport())) {
            return ToolResponse.error("mcp_server_set_header_secure", "安全 Header 只适用于 HTTP MCP Server。");
        }
        if (!safeHeaderName.matches("[A-Za-z0-9][A-Za-z0-9-]{0,127}")) {
            return ToolResponse.error("mcp_server_set_header_secure", "header_name 格式无效。");
        }
        UserInteractionPort port = ToolConfirmationManager.getPort();
        if (port == null || !port.isAvailable()) {
            return ToolResponse.error("mcp_server_set_header_secure", "安全输入界面未就绪，未修改 Header。");
        }

        char[] secret = port.requestSecret(new SecretRequest(
                "设置 MCP 安全 Header",
                "请为 MCP Server「" + serverName + "」输入 " + safeHeaderName
                        + " 的完整值。内容仅交给本地加密配置数据库，不会发送给模型。",
                120,
                16 * 1024));
        if (secret == null || secret.length == 0) {
            if (secret != null) Arrays.fill(secret, '\0');
            return ToolResponse.error("mcp_server_set_header_secure", "用户取消了安全输入或 Header 值为空。");
        }

        try {
            McpServerConfig candidate = copyOf(existing);
            candidate.getHeaders().put(safeHeaderName, new String(secret));
            store.save(candidate);
            if (!candidate.isEnabled()) {
                return ToolResponse.success("mcp_server_set_header_secure",
                        "已加密保存 MCP Server「" + serverName + "」的 " + safeHeaderName
                                + "；服务器当前停用，未建立连接。");
            }
            boolean started = runtime.restart(candidate);
            return started
                    ? ToolResponse.success("mcp_server_set_header_secure",
                            "已加密保存安全 Header 并重新连接 MCP Server「" + serverName + "」。")
                    : ToolResponse.success("mcp_server_set_header_secure",
                            "安全 Header 已确认写入数据库，但服务器重连失败：" + startupSummary(serverName));
        } catch (RuntimeException e) {
            log.error("安全保存 MCP Header 失败: {} header={}", serverName, safeHeaderName, e);
            return ToolResponse.error("mcp_server_set_header_secure", "安全 Header 未保存，请检查数据库后重试。");
        } finally {
            Arrays.fill(secret, '\0');
        }
    }

    @Tool(name = "mcp_server_delete",
            description = "删除一个 MCP Server 配置并停止当前连接。配置删除后不可自动恢复，需用户确认。")
    public String deleteServer(
            @ToolParam(name = "name", description = "服务器名称") String name) {
        String serverName = strip(name);
        if (store.get(serverName) == null) {
            return ToolResponse.error("mcp_server_delete", "未找到 MCP Server: " + serverName);
        }
        if (!ToolConfirmationManager.requestConfirmation(origin, "mcp_server_delete",
                "删除 MCP Server 配置「" + serverName + "」并停止连接")) {
            return ToolResponse.error("mcp_server_delete", "用户取消了删除。");
        }
        try {
            if (!store.delete(serverName)) {
                return ToolResponse.error("mcp_server_delete", "MCP Server 已不存在: " + serverName);
            }
            runtime.stop(serverName);
            return ToolResponse.success("mcp_server_delete", "已删除并停止 MCP Server「" + serverName + "」。");
        } catch (RuntimeException e) {
            log.error("删除 MCP Server 失败: {}", serverName, e);
            return ToolResponse.error("mcp_server_delete", e.getMessage());
        }
    }

    private String saveFromJson(String toolName, String serverName, String configJson,
                                Boolean enabled, boolean update) {
        if (SensitiveDataRedactor.containsLikelyCredential(configJson)) {
            return ToolResponse.error(toolName,
                    "config_json 疑似包含凭据，已拒绝接收；请先保存无秘密配置，再用 mcp_server_set_header_secure 本地输入。"
            );
        }
        McpServerConfig candidate;
        try {
            List<McpServerConfig> parsed = McpJsonImporter.parse(configJson, serverName);
            if (parsed.size() != 1) {
                return ToolResponse.error(toolName, "config_json 必须只包含一个 MCP Server 配置。");
            }
            candidate = parsed.getFirst();
            candidate.setName(serverName);
            if (enabled != null) candidate.setEnabled(enabled);
            validate(candidate);
            if (!candidate.getHeaders().isEmpty()) {
                return ToolResponse.error(toolName,
                        "对话中的 config_json 不允许携带 Header 值；请先保存不含 headers 的配置，再调用 "
                                + "mcp_server_set_header_secure。");
            }
        } catch (IllegalArgumentException e) {
            return ToolResponse.error(toolName, e.getMessage());
        }

        String description = (update ? "更新" : "新增") + " MCP Server「" + serverName + "」："
                + configSummary(candidate) + (candidate.isEnabled() ? "，保存后立即启动" : "，保持停用");
        if (!ToolConfirmationManager.requestConfirmation(origin, toolName, description)) {
            return ToolResponse.error(toolName, "用户取消了 MCP Server 配置保存。");
        }

        try {
            store.save(candidate);
            if (!candidate.isEnabled()) {
                runtime.stop(serverName);
                return ToolResponse.success(toolName,
                        "已确认写入 MCP Server 配置「" + serverName + "」，当前保持停用。");
            }
            boolean started = update ? runtime.restart(candidate) : runtime.start(candidate);
            if (started) {
                McpClientManager.ServerStatus status = runtime.status(serverName);
                return ToolResponse.success(toolName,
                        "已确认写入并启动 MCP Server「" + serverName + "」，发现 "
                                + status.toolCount() + " 个工具。");
            }
            return ToolResponse.success(toolName,
                    "MCP Server 配置「" + serverName + "」已确认写入数据库，但启动失败："
                            + startupSummary(serverName));
        } catch (RuntimeException e) {
            log.error("保存 MCP Server 失败: {}", serverName, e);
            return ToolResponse.error(toolName, e.getMessage());
        }
    }

    static void validate(McpServerConfig config) {
        if (config == null || strip(config.getName()).isEmpty()) {
            throw new IllegalArgumentException("MCP Server name 不能为空。");
        }
        if ("http".equals(config.getTransport())) {
            try {
                ProjectAccessPolicy.requireRemoteMcpEndpoint(strip(config.getUrl()));
            } catch (SecurityException e) {
                throw new IllegalArgumentException(e.getMessage() + "。", e);
            }
        } else {
            if (ProjectAccessPolicy.strictIsolationEnabled()) {
                throw new IllegalArgumentException(
                        "严格项目文件隔离已启用，仅允许 HTTP MCP；本地 stdio MCP 已禁用。");
            }
            if (strip(config.getCommand()).isEmpty()) {
                throw new IllegalArgumentException("stdio MCP 配置必须提供 command。");
            }
        }
    }

    private String startupSummary(String name) {
        // 启动异常可能复述命令参数、URL 查询串或环境变量值；不要把原文带回模型和轨迹日志。
        McpClientManager.ServerStatus status = runtime.status(name);
        return "状态 " + status.state() + "，请在 MCP 中心查看启动详情。";
    }

    private static String configSummary(McpServerConfig config) {
        return "http".equals(config.getTransport())
                ? "HTTP " + ProjectAccessPolicy.remoteEndpointSummary(config.getUrl())
                        + "（Header keys=" + config.getHeaders().keySet() + "）"
                : "stdio command=" + config.getCommand() + "（args=" + config.getArgs().size()
                        + " 项，env keys=" + config.getEnv().keySet() + "）";
    }

    private static McpServerConfig copyOf(McpServerConfig source) {
        McpServerConfig copy = new McpServerConfig();
        copy.setName(source.getName());
        copy.setCommand(source.getCommand());
        copy.setArgs(new ArrayList<>(source.getArgs()));
        copy.setEnv(new LinkedHashMap<>(source.getEnv()));
        copy.setUrl(source.getUrl());
        copy.setHeaders(new LinkedHashMap<>(source.getHeaders()));
        copy.setEnabled(source.isEnabled());
        return copy;
    }

    private static ServerStore liveStore() {
        McpConfigManager manager = McpConfigManager.getInstance();
        return new ServerStore() {
            @Override public List<McpServerConfig> all() { return manager.getAllServers(); }
            @Override public McpServerConfig get(String name) { return manager.getServer(name); }
            @Override public McpServerConfig save(McpServerConfig config) {
                return manager.putServerChecked(config);
            }
            @Override public boolean delete(String name) { return manager.removeServerChecked(name); }
        };
    }

    private static ServerRuntime liveRuntime(McpClientManager manager) {
        return new ServerRuntime() {
            @Override public boolean start(McpServerConfig config) { return manager.startServer(config); }
            @Override public boolean restart(McpServerConfig config) { return manager.restartServer(config); }
            @Override public void stop(String name) { manager.stopServer(name); }
            @Override public McpClientManager.ServerStatus status(String name) {
                return manager.getServerStatus(name);
            }
        };
    }

    private static String strip(String value) {
        return value == null ? "" : value.strip();
    }
}
