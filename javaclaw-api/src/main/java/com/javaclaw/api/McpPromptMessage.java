package com.javaclaw.api;

import java.util.Objects;

/**
 * MCP Prompt 返回的外部消息。
 *
 * @param role 仅 USER 或 ASSISTANT
 * @param content 外部内容块
 */
public record McpPromptMessage(McpSamplingRole role, CanonicalPayload content) {
    /** 校验消息。 */
    public McpPromptMessage {
        Objects.requireNonNull(role, "role");
        Objects.requireNonNull(content, "content");
    }
}
