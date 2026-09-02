package com.javaclaw.api;

import java.util.Optional;

/**
 * MCP Prompt 的声明参数。
 *
 * @param name 参数名
 * @param description 可选说明
 * @param required 是否必填
 */
public record McpPromptArgument(String name, Optional<String> description, boolean required) {
    /** 复制并校验参数。 */
    public McpPromptArgument {
        name = McpResourceDescriptor.boundedText(name, "name", 240);
        description = McpResourceDescriptor.optionalText(description, "description", 2_000);
    }
}
