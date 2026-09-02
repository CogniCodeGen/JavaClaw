package com.javaclaw.model;

import java.util.Objects;

import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import com.javaclaw.api.ToolDescriptor;

/** 只把工具 Schema 交给模型，禁止 Spring AI 代替 Harness 执行工具。 */
final class DefinitionOnlyToolCallback implements ToolCallback {
    private final ToolDefinition definition;

    DefinitionOnlyToolCallback(ToolDescriptor descriptor) {
        Objects.requireNonNull(descriptor, "descriptor");
        definition = ToolDefinition.builder()
                .name(descriptor.identity().name())
                .description(descriptor.description())
                .inputSchema(descriptor.inputSchema().json())
                .build();
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return definition;
    }

    @Override
    public String call(String input) {
        throw new IllegalStateException("工具只能由 JavaClaw Turn Harness 执行");
    }
}
