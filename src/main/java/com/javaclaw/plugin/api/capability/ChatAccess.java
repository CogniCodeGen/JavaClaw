package com.javaclaw.plugin.api.capability;

/**
 * CHAT 能力 —— 插件经此发起一轮 AI 对话。背后是宿主的隔离编排器（与交互聊天完全隔离，
 * 每轮独立上下文），插件无须关心模型、工具路由等细节。
 *
 * <p>需在 {@code plugin.json} 声明 {@link com.javaclaw.plugin.api.Capability#CHAT}。</p>
 *
 * @author JavaClaw
 */
public interface ChatAccess {

    /**
     * 同步对话：阻塞直到拿到完整回复文本。建议在插件自己的后台虚拟线程中调用
     * （阻塞虚拟线程成本极低）。
     *
     * @param prompt 用户输入
     * @return 完整回复文本
     */
    String ask(String prompt);

    /**
     * 流式对话：回复增量经 {@link ChatChunkListener} 逐段回调，方法在对话结束后返回。
     *
     * @param prompt   用户输入
     * @param listener 增量回调
     */
    void stream(String prompt, ChatChunkListener listener);
}
