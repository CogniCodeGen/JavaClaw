package com.javaclaw.api.conversation;

/**
 * 对话回调。
 *
 * <p>{@link ConversationMode#start} 的输出通道，采用“富事件 + 单一终态”双轨回调：
 * <ul>
 *   <li>{@link #onEvent(ConversationEvent)}：模式实时产生的事件流（思考、回复、工具结果等）</li>
 *   <li>{@link #onTerminal(ConversationOutcome)}：完成、取消或失败的唯一终态</li>
 * </ul>
 *
 * <p>每次运行必须且只能触发一次终态。UI 层实现本接口时负责线程切换
 * （例如 JavaFX 侧用 {@code Platform.runLater} 包装）。</p>
 */
public interface ConversationCallbacks {

    /** 接收事件流的每个事件 */
    void onEvent(ConversationEvent event);

    /** 对话唯一终态 */
    void onTerminal(ConversationOutcome outcome);
}
