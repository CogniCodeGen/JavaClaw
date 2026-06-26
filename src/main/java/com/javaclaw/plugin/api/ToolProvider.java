package com.javaclaw.plugin.api;

import java.util.List;

/**
 * 工具提供者 —— 插件入口类<b>可选</b>实现此接口，向聊天编排器贡献可调用工具（host → plugin 方向），
 * 使 agent 能在对话中调用插件功能。
 *
 * <p>不实现此接口的插件即为纯自主运行体（仅 plugin → host 方向，如后台监听）。实现此接口无须声明额外能力，
 * 但工具 handler 内部使用的宿主能力仍受该插件已授权能力约束。</p>
 *
 * <p>宿主在插件 {@code start()} 之后读取 {@link #tools()} 快照；返回的工具在插件停用时一并下线。</p>
 *
 * @author JavaClaw
 */
public interface ToolProvider {

    /**
     * @return 本插件提供给编排器的工具列表（空列表表示不提供）
     */
    List<PluginTool> tools();
}
