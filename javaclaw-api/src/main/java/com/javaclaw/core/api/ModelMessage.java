package com.javaclaw.core.api;

import java.util.List;
import java.util.Objects;

/**
 * Provider 无关的模型上下文消息；工具结果必须同时关联调用标识和工具名。
 *
 * @param role 非空消息角色
 * @param content 非空文本内容，可为空字符串
 * @param toolCallId 工具结果关联的调用标识；非 TOOL 消息可为 null
 * @param toolName 工具结果对应的名称；非 TOOL 消息可为 null
 * @param toolCalls ASSISTANT 发起的工具调用列表；null 归一为空列表，其他角色不得携带调用
 * @param images 当前 USER 消息的有界图片内容；其他角色不允许携带，正文不进入持久 transcript
 */
public record ModelMessage(
        Role role,
        String content,
        String toolCallId,
        String toolName,
        List<ModelToolCall> toolCalls,
        List<ModelImage> images) {
    /** 模型消息的角色，区分用户内容、助手输出和工具返回值。 */
    public enum Role {
        SYSTEM,
        USER,
        ASSISTANT,
        TOOL
    }

    /** 创建无工具调用列表的消息；TOOL 消息还需要工具名，必须使用完整构造器。 */
    public ModelMessage(Role role, String content, String toolCallId) {
        this(role, content, toolCallId, null, List.of(), List.of());
    }

    /** 创建无图片的文本或工具消息，保留工具调用关联。 */
    public ModelMessage(Role role, String content, String toolCallId, String toolName, List<ModelToolCall> toolCalls) {
        this(role, content, toolCallId, toolName, toolCalls, List.of());
    }

    /** 复制工具调用并校验角色关联；拒绝缺少关联信息的 TOOL 消息和非 ASSISTANT 的调用列表。 */
    public ModelMessage {
        role = Objects.requireNonNull(role, "role");
        content = Objects.requireNonNull(content, "content");
        toolCallId = toolCallId == null ? null : toolCallId.strip();
        toolName = toolName == null ? null : toolName.strip();
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
        images = images == null ? List.of() : List.copyOf(images);
        if (images.size() > 20 || (role != Role.USER && !images.isEmpty())) {
            throw new IllegalArgumentException("only user messages may contain at most 20 images");
        }
        if (role != Role.ASSISTANT && !toolCalls.isEmpty()) {
            throw new IllegalArgumentException("only assistant messages may contain tool calls");
        }
        if (role == Role.TOOL
                && (toolCallId == null || toolCallId.isEmpty() || toolName == null || toolName.isEmpty())) {
            throw new IllegalArgumentException("tool messages require call id and tool name");
        }
    }
}
