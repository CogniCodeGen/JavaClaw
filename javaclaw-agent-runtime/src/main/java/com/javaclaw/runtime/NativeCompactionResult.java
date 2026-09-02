package com.javaclaw.runtime;

import java.util.Objects;

/**
 * Provider 原生压缩结果。
 *
 * @param state 压缩后的 opaque state
 * @param consumedTokens 压缩前估算 token
 */
public record NativeCompactionResult(ProviderState state, long consumedTokens) {
    /** 校验结果。 */
    public NativeCompactionResult {
        Objects.requireNonNull(state, "state");
        if (consumedTokens < 0) {
            throw new IllegalArgumentException("consumedTokens must not be negative");
        }
    }
}
