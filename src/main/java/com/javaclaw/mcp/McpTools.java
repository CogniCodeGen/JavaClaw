package com.javaclaw.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.javaclaw.agent.model.ToolResponse;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

/**
 * MCP 工具桥接 — 将 MCP 服务器工具暴露为 AgentScope @Tool 方法
 *
 * <p>提供两个工具方法：
 * <ul>
 *   <li>{@code mcp_list_tools} — 列出所有 MCP 服务器上可用的工具</li>
 *   <li>{@code mcp_call_tool} — 调用指定 MCP 服务器上的工具</li>
 * </ul>
 *
 * @author JavaClaw
 */
public class McpTools {

    private static final Logger log = LoggerFactory.getLogger(McpTools.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();

    private final McpClientManager clientManager;

    public McpTools(McpClientManager clientManager) {
        this.clientManager = clientManager;
    }

    /**
     * 列出所有 MCP 服务器上可用的工具
     */
    @Tool(name = "mcp_list_tools",
          description = "列出所有已连接 MCP 服务器上可用的工具列表，包含工具名称、描述和参数说明")
    public String listTools() {
        try {
            Map<String, List<McpClient.McpToolInfo>> allTools = clientManager.getAllTools();
            if (allTools.isEmpty()) {
                return ToolResponse.error("mcp_list_tools", "没有活跃的 MCP 服务器或没有可用的工具");
            }

            StringBuilder sb = new StringBuilder();
            int totalTools = 0;

            for (Map.Entry<String, List<McpClient.McpToolInfo>> entry : allTools.entrySet()) {
                String serverName = entry.getKey();
                List<McpClient.McpToolInfo> tools = entry.getValue();
                totalTools += tools.size();

                sb.append("## 服务器: ").append(serverName).append("\n\n");
                for (McpClient.McpToolInfo tool : tools) {
                    sb.append("- ").append(tool.getName());
                    if (tool.getDescription() != null && !tool.getDescription().isEmpty()) {
                        sb.append(": ").append(tool.getDescription());
                    }
                    if (tool.getInputSchema() != null) {
                        sb.append("\n  参数 Schema: ").append(tool.getInputSchema().toString());
                    }
                    sb.append("\n");
                }
                sb.append("\n");
            }

            return ToolResponse.success("mcp_list_tools",
                    "共 " + allTools.size() + " 个服务器，" + totalTools + " 个工具\n\n" + sb);
        } catch (Exception e) {
            log.error("列出 MCP 工具失败", e);
            return ToolResponse.fromException("mcp_list_tools", e);
        }
    }

    /**
     * 调用 MCP 服务器上的工具
     *
     * @param serverName    MCP 服务器名称
     * @param toolName      工具名称
     * @param argumentsJson 工具参数（JSON 格式字符串）
     */
    @Tool(name = "mcp_call_tool",
          description = "调用指定 MCP 服务器上的工具。需要指定服务器名称、工具名称和参数（JSON 格式）。" +
                        "调用前可先用 mcp_list_tools 查看可用的服务器和工具。")
    public String callTool(
            @ToolParam(name = "server_name", description = "MCP 服务器名称") String serverName,
            @ToolParam(name = "tool_name", description = "要调用的工具名称") String toolName,
            @ToolParam(name = "arguments_json", description = "工具参数，JSON 对象格式字符串，无参数时传 \"{}\"")
            String argumentsJson) {
        try {
            // 解析参数 JSON
            JsonNode arguments;
            if (argumentsJson == null || argumentsJson.isBlank() || "{}".equals(argumentsJson.trim())) {
                arguments = objectMapper.createObjectNode();
            } else {
                arguments = objectMapper.readTree(argumentsJson);
            }

            log.info("调用 MCP 工具: {}@{} 参数: {}", toolName, serverName, argumentsJson);
            String result = clientManager.callTool(serverName, toolName, arguments);
            log.info("MCP 工具调用成功: {}@{}", toolName, serverName);

            return ToolResponse.success("mcp_call_tool",
                    "[" + serverName + "/" + toolName + "] " + result);
        } catch (Exception e) {
            log.error("调用 MCP 工具失败: {}@{}", toolName, serverName, e);
            return ToolResponse.fromException("mcp_call_tool", e);
        }
    }
}
