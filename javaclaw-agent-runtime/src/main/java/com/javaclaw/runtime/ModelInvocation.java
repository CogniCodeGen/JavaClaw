package com.javaclaw.runtime;

import java.util.List;
import java.util.Objects;

import com.javaclaw.api.ToolDescriptor;

/**
 * Provider 无关的模型调用。
 *
 * @param modelId Provider registry 中的端点标识
 * @param instructions 已审阅并冻结的分层指令
 * @param messages 当前上下文窗口
 * @param tools 本轮可见工具；是冻结目录的子集
 * @param maximumOutputTokens 本轮最大输出 token
 * @param reasoning 本 Turn 冻结推理偏好；缺省由 Provider 使用其默认值
 */
public record ModelInvocation(
        String modelId,
        ModelInstructions instructions,
        List<ModelMessage> messages,
        List<ToolDescriptor> tools,
        long maximumOutputTokens,
        java.util.Optional<com.javaclaw.api.ReasoningPreference> reasoning) {
    /** 复制集合并校验模型输入。 */
    public ModelInvocation {
        modelId = text(modelId, "modelId");
        Objects.requireNonNull(instructions, "instructions");
        reasoning = Objects.requireNonNull(reasoning, "reasoning");
        messages = List.copyOf(messages);
        tools = List.copyOf(tools);
        if (maximumOutputTokens < 1) {
            throw new IllegalArgumentException("maximumOutputTokens must be positive");
        }
    }

    /**
     * 构造仅有平台指令的模型调用，适用于无需 Role 的验证探针。
     *
     * @param modelId 精确模型路由
     * @param systemInstruction 平台指令
     * @param messages 数据消息
     * @param tools 可用工具
     * @param maximumOutputTokens 输出上限
     */
    public ModelInvocation(
            String modelId,
            String systemInstruction,
            List<ModelMessage> messages,
            List<ToolDescriptor> tools,
            long maximumOutputTokens) {
        this(
                modelId,
                new ModelInstructions(systemInstruction, "", ""),
                messages,
                tools,
                maximumOutputTokens,
                java.util.Optional.empty());
    }

    /** @return 已冻结的平台层，不包含 developer 指令 */
    public String systemInstruction() {
        return instructions.systemInstruction();
    }

    private static String text(String value, String name) {
        String normalized = Objects.requireNonNull(value, name).strip();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return normalized;
    }
}
