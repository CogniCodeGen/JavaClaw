package com.javaclaw.api.conversation;

/**
 * 对话类模式接口（消息流式交互）。
 *
 * <p>普通聊天 / 规划模式均实现本接口。UI 在用户按"发送"时调用 {@link #start}
 * 传入本次请求与回调，模式内部通过 {@link ConversationEvent} 实时推送思考、
 * 回复、工具结果等事件。</p>
 *
 * <p>本接口不依赖任何 UI 框架，Web / CLI / JavaFX 均可直接消费。</p>
 */
public interface ConversationMode extends Mode {

    /**
     * 启动一次对话。
     *
     * <p>事件流应通过 {@code callbacks} 异步推送；本方法应该立即返回（不阻塞调用线程）。
     * 对话结束时触发一次 {@link ConversationCallbacks#onTerminal(ConversationOutcome)}。</p>
     *
     * @param request   用户请求（文本 + 附件）
     * @param callbacks 事件与生命周期回调
     */
    ConversationHandle start(ConversationRequest request, ConversationCallbacks callbacks);

    /**
     * 清空模式内部的对话历史。
     *
     * <p>典型用于用户主动清空、删除会话、切换会话等场景。默认空实现。</p>
     */
    default void clearHistory() {
    }
}
