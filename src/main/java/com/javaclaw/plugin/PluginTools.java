package com.javaclaw.plugin;

import com.javaclaw.agent.model.ToolResponse;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 插件工具桥 —— 把已启用插件贡献的工具暴露给聊天编排器（host → plugin 方向）。
 *
 * <p>照 MCP 工具的"单派发器"范式：{@code plugin_list_tools} 列出可用工具，
 * {@code plugin_call_tool} 按 (plugin_id, tool_name, arguments_json) 派发。可用工具同时也由
 * {@link PluginManager#buildToolsPrompt()} 注入系统提示词，故 agent 通常无须先 list 即可直接调用。</p>
 *
 * @author JavaClaw
 */
public final class PluginTools {

    private static final Logger log = LoggerFactory.getLogger(PluginTools.class);

    @Tool(name = "plugin_list_tools",
            description = "列出当前已启用插件提供的可调用工具及其参数")
    public String listTools() {
        String prompt = PluginManager.getInstance().buildToolsPrompt();
        return ToolResponse.success("plugin_list_tools",
                prompt.isBlank() ? "当前没有已启用插件提供工具。" : prompt.strip());
    }

    @Tool(name = "plugin_call_tool",
            description = "调用某个已启用插件提供的工具。plugin_id 与 tool_name 见 plugin_list_tools 或系统提示词中的「插件工具」清单")
    public String callTool(
            @ToolParam(name = "plugin_id", description = "插件 id") String pluginId,
            @ToolParam(name = "tool_name", description = "工具名") String toolName,
            @ToolParam(name = "arguments_json",
                    description = "工具参数，JSON 对象字符串；无参数时传 \"{}\"") String argumentsJson) {
        String args = (argumentsJson == null || argumentsJson.isBlank()) ? "{}" : argumentsJson;
        try {
            String result = PluginManager.getInstance().invokeTool(pluginId, toolName, args);
            return ToolResponse.success("plugin_call_tool", result == null ? "（无输出）" : result);
        } catch (Exception e) {
            log.warn("插件工具调用失败 plugin_id={} tool_name={}：{}", pluginId, toolName, e.toString());
            return ToolResponse.error("plugin_call_tool",
                    "调用插件[" + pluginId + "]工具[" + toolName + "]失败：" + e.getMessage());
        }
    }
}
