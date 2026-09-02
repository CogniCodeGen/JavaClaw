package com.javaclaw.runtime;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

import com.javaclaw.api.MessageRole;

/**
 * 发送到通用模型抽象的消息。
 *
 * @param role 消息角色
 * @param text 正文
 * @param toolCalls Assistant 消息中的完整工具调用
 * @param toolCallId Tool 消息关联 ID；其他角色为空
 * @param toolName Tool 消息关联名称；其他角色为空
 */
public record ModelMessage(
        MessageRole role,
        String text,
        List<ModelToolCall> toolCalls,
        Optional<String> toolCallId,
        Optional<String> toolName) {
    /** 校验角色约束。 */
    public ModelMessage {
        Objects.requireNonNull(role, "role");
        text = Objects.requireNonNull(text, "text");
        toolCalls = List.copyOf(toolCalls);
        toolCallId = normalized(toolCallId, "toolCallId");
        toolName = normalized(toolName, "toolName");
        if (role == MessageRole.TOOL && (toolCallId.isEmpty() || toolName.isEmpty())) {
            throw new IllegalArgumentException("TOOL messages require toolCallId and toolName");
        }
        if (role != MessageRole.TOOL && (toolCallId.isPresent() || toolName.isPresent())) {
            throw new IllegalArgumentException("tool response identity is only valid for TOOL messages");
        }
        if (role != MessageRole.ASSISTANT && !toolCalls.isEmpty()) {
            throw new IllegalArgumentException("toolCalls are only valid for ASSISTANT messages");
        }
    }

    /**
     * 创建 assistant 消息。
     *
     * @param text 正文
     * @param toolCalls 完整工具调用
     * @return 消息
     */
    public static ModelMessage assistant(String text, List<ModelToolCall> toolCalls) {
        return new ModelMessage(MessageRole.ASSISTANT, text, toolCalls, Optional.empty(), Optional.empty());
    }

    /**
     * 创建 tool 回填消息。
     *
     * @param callId 调用 ID
     * @param toolName 工具名称
     * @param text 已脱敏结果
     * @return 消息
     */
    public static ModelMessage tool(String callId, String toolName, String text) {
        return new ModelMessage(MessageRole.TOOL, text, List.of(), Optional.of(callId), Optional.of(toolName));
    }

    private static Optional<String> normalized(Optional<String> value, String name) {
        return Objects.requireNonNull(value, name).map(String::strip).filter(entry -> !entry.isEmpty());
    }
}
