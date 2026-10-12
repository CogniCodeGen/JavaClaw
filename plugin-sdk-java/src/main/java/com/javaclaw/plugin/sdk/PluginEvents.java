package com.javaclaw.plugin.sdk;

/** 插件只发送数据，终结与错误事件由 Runner 统一封装。 */
@FunctionalInterface
public interface PluginEvents {
    void data(String payloadJson);
}
