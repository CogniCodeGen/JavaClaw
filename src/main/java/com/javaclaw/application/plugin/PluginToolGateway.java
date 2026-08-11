package com.javaclaw.application.plugin;

/**
 * Agent 编排器访问插件工具的应用端口。
 *
 * <p>实现线程安全。工具目录返回不可变文本快照；调用可能阻塞并支持插件运行时定义的
 * 协作取消。插件不存在、未启用或执行失败时抛出异常，由统一工具结果边界映射。</p>
 */
public interface PluginToolGateway {

    String buildToolsPrompt();

    String invokeTool(String pluginId, String toolName, String argumentsJson) throws Exception;
}
