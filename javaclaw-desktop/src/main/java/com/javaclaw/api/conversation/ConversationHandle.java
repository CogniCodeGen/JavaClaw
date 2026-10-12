package com.javaclaw.api.conversation;

/** 一次独立对话运行的控制句柄。 */
public interface ConversationHandle {

    /**
     * 取消当前运行。只有成功接管取消请求时返回 {@code true}；终态运行返回
     * {@code false}。
     */
    boolean cancel(CancellationReason reason);

    boolean isTerminal();
}
