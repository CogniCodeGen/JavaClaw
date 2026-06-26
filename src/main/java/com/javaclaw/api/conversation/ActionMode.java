package com.javaclaw.api.conversation;

/**
 * 动作类模式接口（触发独立窗口或动作）。
 *
 * <p>托管任务模式实现此接口：它不在聊天消息流中跑，而是"打开一个独立的任务视图"。
 * 这类模式并不消费 {@link ConversationRequest}，也不产生 {@link ConversationEvent}。</p>
 *
 * <p>{@link #open()} 的具体渲染由 UI 适配器实现：JavaFX 下可能打开一个 Stage，
 * Web 下可能跳转到一个独立路由。</p>
 */
public interface ActionMode extends Mode {

    /** 激活此模式：通常是打开一个对话框 / 新窗口 / 新页面 */
    void open();
}
