package com.javaclaw.sdk.model;

import java.util.Map;

/**
 * 初始化后协商得到的服务端身份和能力快照。
 *
 * @param protocolVersion 协商后的 JavaClaw 协议版本，不是 JSON-RPC 的 2.0 字段
 * @param name 展示名称或资源名称；有效服务端响应中非空
 * @param version 组件或声明的版本字符串
 * @param capabilities 能力开关快照；null 归一为空 Map
 * @param connectionId 当前本地连接标识，用于路由和诊断
 */
public record ServerInfo(
        int protocolVersion, String name, String version, Map<String, Boolean> capabilities, String connectionId) {
    /** 复制协商能力 Map，避免连接建立后被调用方改变能力视图。 */
    public ServerInfo {
        capabilities = capabilities == null ? Map.of() : Map.copyOf(capabilities);
    }
}
