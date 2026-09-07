package com.javaclaw.runtime;

import java.util.Objects;

/**
 * Provider 原生压缩结果。
 *
 * @param state 压缩后的 opaque state
 * @param consumedTokens 压缩前估算 token
 * @param usage Provider 报告的完整用量
 * @param estimatedInputTokens 压缩后 opaque 表示的输入估算，不得用压缩前 usage 冒充
 */
public record NativeCompactionResult(
        ProviderState state, long consumedTokens, ModelUsage usage, long estimatedInputTokens) {
    /** 校验结果。 */
    public NativeCompactionResult {
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(usage, "usage");
        if (consumedTokens < 0 || estimatedInputTokens < 0) {
            throw new IllegalArgumentException("consumedTokens must not be negative");
        }
    }

    /**
     * 兼容未提供 usage 的本地实现；生产 Adapter 必须使用完整结果构造器。
     *
     * @param state opaque state
     * @param consumedTokens 压缩前估算
     */
    public NativeCompactionResult(ProviderState state, long consumedTokens) {
        this(
                state,
                consumedTokens,
                ModelUsage.zero(),
                ContextTokenEstimator.text(state.payload().json()));
    }
}
