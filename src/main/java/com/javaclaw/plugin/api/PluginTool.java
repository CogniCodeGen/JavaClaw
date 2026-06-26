package com.javaclaw.plugin.api;

import java.util.List;

/**
 * 插件对外提供给聊天编排器的一个工具 —— 让 agent 能在对话中调用插件功能（host → plugin 方向）。
 *
 * <p>插件通过实现 {@link ToolProvider} 声明一组本类型的工具。宿主把它们经统一的派发器
 * （{@code plugin_call_tool}）暴露给编排器，并在系统提示词中列出其名称/描述/参数。</p>
 *
 * @param name        工具名（同一插件内唯一；agent 经 {@code tool_name} 指定）
 * @param description 工具用途描述（指导 agent 何时调用、如何传参）
 * @param params      参数声明（用于提示 agent 构造 arguments_json）
 * @param handler     执行体
 * @author JavaClaw
 */
public record PluginTool(
        String name,
        String description,
        List<Param> params,
        PluginToolHandler handler) {

    public PluginTool {
        params = params == null ? List.of() : List.copyOf(params);
    }

    /**
     * 工具参数声明。
     *
     * @param name        参数名（arguments_json 中的键）
     * @param description 参数说明
     * @param required    是否必填
     */
    public record Param(String name, String description, boolean required) {
    }
}
