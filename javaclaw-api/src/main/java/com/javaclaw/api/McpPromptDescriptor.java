package com.javaclaw.api;

import java.util.List;
import java.util.Optional;

/**
 * MCP Endpoint 声明的外部 Prompt 模板；声明内容不会自动成为系统指令。
 *
 * @param name 模板名
 * @param title 可选标题
 * @param description 可选说明
 * @param arguments 有界参数声明
 */
public record McpPromptDescriptor(
        String name, Optional<String> title, Optional<String> description, List<McpPromptArgument> arguments) {
    /** 复制并校验描述。 */
    public McpPromptDescriptor {
        name = McpResourceDescriptor.boundedText(name, "name", 240);
        title = McpResourceDescriptor.optionalText(title, "title", 500);
        description = McpResourceDescriptor.optionalText(description, "description", 4_000);
        arguments = List.copyOf(arguments);
        if (arguments.size() > 32) {
            throw new IllegalArgumentException("prompt must not exceed 32 arguments");
        }
    }
}
