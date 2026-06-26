package com.javaclaw.api.conversation;

/**
 * 对话回调。
 *
 * <p>{@link ConversationMode#start} 的输出通道，采用"富事件 + 生命周期终态"双轨回调：
 * <ul>
 *   <li>{@link #onEvent(ConversationEvent)}：模式实时产生的事件流（思考、回复、工具结果等）</li>
 *   <li>{@link #onComplete()}：对话正常结束</li>
 *   <li>{@link #onError(Throwable)}：对话异常结束</li>
 * </ul>
 *
 * <p>{@code onComplete} / {@code onError} 互斥，触发且仅触发其中一次。UI 层实现本接口
 * 时负责线程切换（例如 JavaFX 侧用 {@code Platform.runLater} 包装）。</p>
 */
public interface ConversationCallbacks {

    /** 接收事件流的每个事件 */
    void onEvent(ConversationEvent event);

    /** 对话成功结束 */
    void onComplete();

    /** 对话异常结束 */
    void onError(Throwable error);
}
