package com.javaclaw.runtime;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 已按 Thread sequence 排序的模型上下文窗口。
 *
 * @param messages 消息
 * @param providerState 可选 opaque state
 * @param estimatedInputTokens 当前估算 token
 * @param fixedInputTokens 估算中已包含的固定指令与工具 Schema token；旧快照为 0
 */
public record ConversationWindow(
        List<ModelMessage> messages,
        Optional<ProviderState> providerState,
        long estimatedInputTokens,
        long fixedInputTokens) {
    /**
     * 从尚未核算固定内容的窗口创建快照。
     *
     * @param messages 有序消息
     * @param providerState 可选原生状态
     * @param estimatedInputTokens 输入估算
     */
    public ConversationWindow(
            List<ModelMessage> messages, Optional<ProviderState> providerState, long estimatedInputTokens) {
        this(messages, providerState, estimatedInputTokens, 0);
    }

    /** 复制窗口并校验 token。 */
    public ConversationWindow {
        messages = List.copyOf(messages);
        providerState = Objects.requireNonNull(providerState, "providerState");
        if (estimatedInputTokens < 0 || fixedInputTokens < 0 || fixedInputTokens > estimatedInputTokens) {
            throw new IllegalArgumentException("estimatedInputTokens must not be negative");
        }
    }

    /**
     * 追加消息，估算由调用方更新。
     *
     * @param additional 新消息
     * @param additionalTokens 新增 token 估算
     * @return 新窗口
     */
    public ConversationWindow append(List<ModelMessage> additional, long additionalTokens) {
        java.util.ArrayList<ModelMessage> combined = new java.util.ArrayList<>(messages);
        combined.addAll(additional);
        return new ConversationWindow(
                combined,
                providerState,
                Math.addExact(estimatedInputTokens, Math.max(0, additionalTokens)),
                fixedInputTokens);
    }

    /**
     * 更新 Provider state。
     *
     * @param state 新 state
     * @return 新窗口
     */
    public ConversationWindow withProviderState(Optional<ProviderState> state) {
        return new ConversationWindow(messages, state, estimatedInputTokens, fixedInputTokens);
    }
}
