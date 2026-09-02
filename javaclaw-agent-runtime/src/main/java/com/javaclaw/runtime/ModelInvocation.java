package com.javaclaw.runtime;

import java.util.List;
import java.util.Objects;

import com.javaclaw.api.ToolDescriptor;

/**
 * Provider 无关的模型调用。
 *
 * @param modelId Provider registry 中的端点标识
 * @param systemInstruction 已审阅并冻结的系统说明
 * @param messages 当前上下文窗口
 * @param tools 本轮可见工具；是冻结目录的子集
 * @param maximumOutputTokens 本轮最大输出 token
 */
public record ModelInvocation(
        String modelId,
        String systemInstruction,
        List<ModelMessage> messages,
        List<ToolDescriptor> tools,
        long maximumOutputTokens) {
    /** 复制集合并校验模型输入。 */
    public ModelInvocation {
        modelId = text(modelId, "modelId");
        systemInstruction = Objects.requireNonNull(systemInstruction, "systemInstruction");
        messages = List.copyOf(messages);
        tools = List.copyOf(tools);
        if (maximumOutputTokens < 1) {
            throw new IllegalArgumentException("maximumOutputTokens must be positive");
        }
    }

    private static String text(String value, String name) {
        String normalized = Objects.requireNonNull(value, name).strip();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return normalized;
    }
}
