package com.javaclaw.inference.api;

/** 模型产生的工具调用。argumentsJson 保留模型的原始 JSON。 */
public record InferenceToolCall(String id, String name, String argumentsJson) {

    public InferenceToolCall {
        id = requireText(id, "工具调用 ID");
        name = requireText(name, "工具名称");
        argumentsJson = argumentsJson == null || argumentsJson.isBlank() ? "{}" : argumentsJson;
    }

    private static String requireText(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + "不能为空");
        }
        return value.strip();
    }
}
