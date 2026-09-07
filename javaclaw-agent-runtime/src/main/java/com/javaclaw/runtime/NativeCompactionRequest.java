package com.javaclaw.runtime;

import java.util.List;
import java.util.Objects;

import com.javaclaw.api.TurnId;

/**
 * Provider 原生压缩请求。
 *
 * @param turnId Turn
 * @param modelId 模型端点
 * @param state 当前 opaque state
 * @param messages state 尚未包含的完整增量消息
 * @param targetInputTokens 本次压缩目标，不改变累计预算
 */
public record NativeCompactionRequest(
        TurnId turnId, String modelId, ProviderState state, List<ModelMessage> messages, long targetInputTokens) {
    /** 校验请求。 */
    public NativeCompactionRequest {
        Objects.requireNonNull(turnId, "turnId");
        modelId = Objects.requireNonNull(modelId, "modelId").strip();
        if (modelId.isEmpty()) {
            throw new IllegalArgumentException("modelId must not be blank");
        }
        Objects.requireNonNull(state, "state");
        messages = List.copyOf(messages);
        if (targetInputTokens < 1) {
            throw new IllegalArgumentException("targetInputTokens must be positive");
        }
    }

    /**
     * 仅压缩已持有的 state，不添加增量消息。
     *
     * @param turnId Turn
     * @param modelId 精确模型路由
     * @param state opaque state
     */
    public NativeCompactionRequest(TurnId turnId, String modelId, ProviderState state) {
        this(turnId, modelId, state, List.of(), ModelContextPolicy.fallback().windowTokens());
    }
}
