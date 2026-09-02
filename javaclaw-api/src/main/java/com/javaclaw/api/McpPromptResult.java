package com.javaclaw.api;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * MCP Prompt 显式获取结果；调用方只能将其作为外部数据展示或经治理后使用。
 *
 * @param description 可选说明
 * @param messages 外部 USER/ASSISTANT 消息
 * @param progress 已校验进度
 */
public record McpPromptResult(
        Optional<String> description, List<McpPromptMessage> messages, List<McpProgress> progress) {
    /** 复制并校验结果。 */
    public McpPromptResult {
        description = McpResourceDescriptor.optionalText(description, "description", 4_000);
        messages = List.copyOf(messages);
        if (messages.isEmpty() || messages.size() > 64) {
            throw new IllegalArgumentException("prompt result must contain 1 to 64 messages");
        }
        progress = List.copyOf(progress);
        Objects.requireNonNull(progress, "progress");
    }
}
