package com.javaclaw.runtime;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Provider 调用的统一结果。
 *
 * @param text 可见文本，可为空
 * @param toolCalls 完整工具调用
 * @param usage 本次调用 usage
 * @param reasoningSummary 可选推理摘要
 * @param providerState 可选 opaque state
 * @param finishReason 停止原因
 */
public record ModelInvocationResult(
        String text,
        List<ModelToolCall> toolCalls,
        ModelUsage usage,
        Optional<String> reasoningSummary,
        Optional<ProviderState> providerState,
        ModelFinishReason finishReason) {
    /** 复制列表并校验结果。 */
    public ModelInvocationResult {
        text = Objects.requireNonNull(text, "text");
        toolCalls = List.copyOf(toolCalls);
        Objects.requireNonNull(usage, "usage");
        reasoningSummary = Objects.requireNonNull(reasoningSummary, "reasoningSummary");
        providerState = Objects.requireNonNull(providerState, "providerState");
        Objects.requireNonNull(finishReason, "finishReason");
        if (toolCalls.isEmpty() == (finishReason == ModelFinishReason.TOOL_CALLS)) {
            throw new IllegalArgumentException("tool calls must match TOOL_CALLS finish reason");
        }
    }
}
