package com.javaclaw.api;

import java.util.List;

/**
 * MCP 受限 sampling 请求；不允许 system 内容、自动附加 Context 或工具。
 *
 * @param id 请求标识
 * @param endpointId 来源端点
 * @param messages 不可信 USER/ASSISTANT 消息
 * @param maximumOutputTokens 最大输出 token
 */
public record McpSamplingRequest(
        String id, String endpointId, List<McpSamplingMessage> messages, int maximumOutputTokens) {
    /** 复制消息并实施硬限制。 */
    public McpSamplingRequest {
        id = Preconditions.identifier(id, "id");
        endpointId = Preconditions.identifier(endpointId, "endpointId");
        messages = List.copyOf(messages);
        if (messages.isEmpty() || messages.size() > 32) {
            throw new IllegalArgumentException("sampling messages must contain 1 to 32 entries");
        }
        if (maximumOutputTokens < 1 || maximumOutputTokens > 2048) {
            throw new IllegalArgumentException("maximumOutputTokens must be between 1 and 2048");
        }
    }
}
