package com.javaclaw.protocol;

import java.util.Map;

/**
 * 握手结果与能力协商快照；客户端收到后仍需发送 initialized。
 *
 * @param protocolVersion 协商后的 JavaClaw 协议版本，不是 JSON-RPC 的 2.0 字段
 * @param serverName 服务端名称
 * @param serverVersion 服务端发行版本
 * @param capabilities 能力开关快照；null 归一为空 Map
 * @param connectionId 当前本地连接标识，用于路由和诊断
 */
public record InitializeResult(
        int protocolVersion,
        String serverName,
        String serverVersion,
        Map<String, Boolean> capabilities,
        String connectionId) {
    /** 复制协商能力 Map，避免连接建立后被调用方改变能力视图。 */
    public InitializeResult {
        capabilities = capabilities == null ? Map.of() : Map.copyOf(capabilities);
    }
}
