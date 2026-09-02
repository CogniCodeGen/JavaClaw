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
 */
public record ConversationWindow(
        List<ModelMessage> messages, Optional<ProviderState> providerState, long estimatedInputTokens) {
    /** 复制窗口并校验 token。 */
    public ConversationWindow {
        messages = List.copyOf(messages);
        providerState = Objects.requireNonNull(providerState, "providerState");
        if (estimatedInputTokens < 0) {
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
                combined, providerState, Math.addExact(estimatedInputTokens, Math.max(0, additionalTokens)));
    }

    /**
     * 更新 Provider state。
     *
     * @param state 新 state
     * @return 新窗口
     */
    public ConversationWindow withProviderState(Optional<ProviderState> state) {
        return new ConversationWindow(messages, state, estimatedInputTokens);
    }
}
