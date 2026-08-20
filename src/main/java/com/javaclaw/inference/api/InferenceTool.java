package com.javaclaw.inference.api;

import java.util.Map;

/** 仅提供给原始模型的工具定义；本地推理网关绝不执行宿主工具。 */
public record InferenceTool(String name, String description, Map<String, Object> inputSchema) {

    public InferenceTool {
        name = requireText(name, "工具名称");
        description = description == null ? "" : description;
        inputSchema = inputSchema == null ? Map.of() : Map.copyOf(inputSchema);
    }

    private static String requireText(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + "不能为空");
        }
        return value.strip();
    }
}
