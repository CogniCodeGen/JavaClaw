package com.javaclaw.api;

import java.util.Objects;

/**
 * MCP sampling 的不可信消息。
 *
 * @param role 仅 USER 或 ASSISTANT
 * @param content 结构化外部数据
 */
public record McpSamplingMessage(McpSamplingRole role, CanonicalPayload content) {
    /** 校验消息。 */
    public McpSamplingMessage {
        Objects.requireNonNull(role, "role");
        Objects.requireNonNull(content, "content");
    }
}
