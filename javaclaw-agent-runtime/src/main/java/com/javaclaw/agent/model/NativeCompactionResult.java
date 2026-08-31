package com.javaclaw.agent.model;

import java.util.Objects;

import com.javaclaw.core.api.ModelUsage;
import com.javaclaw.core.api.ProviderConversationState;

/**
 * 原生 compact endpoint 的 SDK 无关结果。
 *
 * @param state 必须原样用于下一次同 Provider 输入的 canonical opaque 状态
 * @param usage 本次压缩用量
 */
public record NativeCompactionResult(ProviderConversationState state, ModelUsage usage) {
    /** 要求 compact 返回可持久化状态；用量缺失归一为零。 */
    public NativeCompactionResult {
        state = Objects.requireNonNull(state, "state").persistent();
        usage = usage == null ? ModelUsage.ZERO : usage;
    }
}
