package com.javaclaw.inference.api;

import java.util.List;

/** 与具体模型 SDK 无关的对话消息。 */
public record InferenceMessage(
        Role role,
        String content,
        String reasoningContent,
        String toolCallId,
        List<InferenceToolCall> toolCalls) {

    public InferenceMessage {
        if (role == null) throw new IllegalArgumentException("消息角色不能为空");
        content = content == null ? "" : content;
        reasoningContent = reasoningContent == null ? "" : reasoningContent;
        toolCallId = toolCallId == null ? "" : toolCallId;
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
    }

    public enum Role { SYSTEM, USER, ASSISTANT, TOOL }
}
