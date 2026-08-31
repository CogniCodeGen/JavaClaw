package com.javaclaw.core.api;

import java.util.List;
import java.util.Objects;

/**
 * 单次云模型调用的输入快照；只包含本次可见工具和已组装的上下文。
 *
 * @param threadId 所属 Thread 的非空标识
 * @param turnId 所属 Turn 的非空标识
 * @param messages 按上下文顺序排列的非空消息列表，构造时复制
 * @param tools 可见工具描述符；null 归一为空列表
 * @param config 本次执行使用的非空 Turn 配置快照
 * @param conversationState 同一 Provider 的可选 opaque 对话窗口；null 表示从消息开始
 * @param conversationStartIndex messages 中动态对话开始的位置；此前内容每次从原始策略重新编译
 */
public record ModelRequest(
        ThreadId threadId,
        TurnId turnId,
        List<ModelMessage> messages,
        List<ToolDescriptor> tools,
        TurnConfig config,
        ProviderConversationState conversationState,
        int conversationStartIndex) {
    /** 为不管理 Provider opaque 状态的网关创建完整消息请求。 */
    public ModelRequest(
            ThreadId threadId,
            TurnId turnId,
            List<ModelMessage> messages,
            List<ToolDescriptor> tools,
            TurnConfig config) {
        this(threadId, turnId, messages, tools, config, null, 1);
    }

    /** 复制消息和工具列表，防止 Provider 调用期间被其他代码改写。 */
    public ModelRequest {
        threadId = Objects.requireNonNull(threadId, "threadId");
        turnId = Objects.requireNonNull(turnId, "turnId");
        messages = List.copyOf(Objects.requireNonNull(messages, "messages"));
        tools = tools == null ? List.of() : List.copyOf(tools);
        config = Objects.requireNonNull(config, "config");
        if (conversationStartIndex < 1 || conversationStartIndex > messages.size()) {
            throw new IllegalArgumentException("conversationStartIndex is outside the message list");
        }
    }
}
