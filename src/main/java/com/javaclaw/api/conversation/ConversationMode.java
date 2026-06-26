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
     * 对话完成或异常时各自触发 {@link ConversationCallbacks#onComplete()} /
     * {@link ConversationCallbacks#onError(Throwable)}，两者互斥。</p>
     *
     * @param request   用户请求（文本 + 附件）
     * @param callbacks 事件与生命周期回调
     */
    void start(ConversationRequest request, ConversationCallbacks callbacks);

    /**
     * 取消当前进行中的对话。
     *
     * <p>能取消返回 true；没有活跃对话或不支持取消返回 false。取消后不再触发
     * onEvent，已经在途的事件可能因为异步仍会到达，UI 应自行处理"流代次"。</p>
     */
    default boolean cancel() {
        return false;
    }

    /**
     * 清空模式内部的对话历史。
     *
     * <p>典型用于用户主动清空、删除会话、切换会话等场景。默认空实现。</p>
     */
    default void clearHistory() {
    }
}
