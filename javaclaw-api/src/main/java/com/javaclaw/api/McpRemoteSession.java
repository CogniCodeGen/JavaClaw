package com.javaclaw.api;

import java.util.Set;

/**
 * MCP initialize 握手的脱敏结果。
 *
 * @param protocolVersion 远端确认的协议版本
 * @param capabilities 远端 capability 名称
 */
public record McpRemoteSession(String protocolVersion, Set<String> capabilities) {
    /** 复制 capability 并校验协议文本。 */
    public McpRemoteSession {
        protocolVersion = Preconditions.text(protocolVersion, "protocolVersion");
        capabilities = capabilities.stream()
                .map(value -> Preconditions.identifier(value, "capability"))
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }
}
