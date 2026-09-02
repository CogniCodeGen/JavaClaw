package com.javaclaw.runtime;

import java.util.Objects;

import com.javaclaw.api.TurnId;

/**
 * Provider 原生压缩请求。
 *
 * @param turnId Turn
 * @param modelId 模型端点
 * @param state 当前 opaque state
 */
public record NativeCompactionRequest(TurnId turnId, String modelId, ProviderState state) {
    /** 校验请求。 */
    public NativeCompactionRequest {
        Objects.requireNonNull(turnId, "turnId");
        modelId = Objects.requireNonNull(modelId, "modelId").strip();
        if (modelId.isEmpty()) {
            throw new IllegalArgumentException("modelId must not be blank");
        }
        Objects.requireNonNull(state, "state");
    }
}
