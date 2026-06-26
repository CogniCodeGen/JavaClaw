package com.javaclaw.plugin.api.capability;

/**
 * AI 流式对话的增量回调 —— 传给 {@code ChatAccess.stream(...)}，逐段接收回复。
 *
 * <p>所有回调由宿主在编排线程上触发；插件回调实现应轻量、不阻塞。</p>
 *
 * @author JavaClaw
 */
public interface ChatChunkListener {

    /**
     * 收到一段回复增量。
     *
     * @param text 文本增量（可能为短句或单字，按模型流式粒度而定）
     */
    void onChunk(String text);

    /**
     * 本轮对话正常结束（所有增量已推送完毕）。
     */
    void onComplete();

    /**
     * 本轮对话出错。
     *
     * @param message 错误简述
     */
    void onError(String message);
}
