package com.javaclaw.core.api;

import java.util.List;

/**
 * 一次模型调用的最终结果；reasoningSummary 仅存可展示摘要，绝不存原始思维链。
 *
 * @param text 最终助手文本；null 归一为空字符串
 * @param reasoningSummary 可展示的推理摘要；null 归一为空字符串
 * @param toolCalls 完整组装的工具调用列表；null 归一为空列表
 * @param usage token 用量；null 归一为 ModelUsage.ZERO
 * @param conversationState Provider 返回的 canonical 对话状态；不支持时为空
 */
public record ModelResponse(
        String text,
        String reasoningSummary,
        List<ModelToolCall> toolCalls,
        ModelUsage usage,
        ProviderConversationState conversationState) {
    /** 创建不携带 Provider 对话状态的兼容响应。 */
    public ModelResponse(String text, String reasoningSummary, List<ModelToolCall> toolCalls, ModelUsage usage) {
        this(text, reasoningSummary, toolCalls, usage, null);
    }

    /** 归一可选输出并复制工具调用；最终响应与流式片段分离，供持久 Item 收尾。 */
    public ModelResponse {
        text = text == null ? "" : text;
        reasoningSummary = reasoningSummary == null ? "" : reasoningSummary;
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
        usage = usage == null ? ModelUsage.ZERO : usage;
    }
}
