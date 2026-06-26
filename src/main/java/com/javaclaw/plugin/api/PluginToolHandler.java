package com.javaclaw.plugin.api;

/**
 * 插件工具的执行体 —— 收到调用方（聊天编排器）传来的 JSON 参数字符串，返回结果文本。
 *
 * <p>由宿主在该插件的<b>托管虚拟线程</b>上、且<b>插件身份作用域</b>内调用，故 handler 内可安全使用
 * {@code PluginContext} 的各项能力（受该插件已授权能力约束）。</p>
 *
 * @author JavaClaw
 */
@FunctionalInterface
public interface PluginToolHandler {

    /**
     * 执行工具。
     *
     * @param argumentsJson 调用参数，JSON 对象字符串（无参数时为 {@code "{}"}）
     * @return 结果文本（将回传给聊天编排器）
     * @throws Exception 执行失败；宿主会包装为工具错误返回
     */
    String call(String argumentsJson) throws Exception;
}
