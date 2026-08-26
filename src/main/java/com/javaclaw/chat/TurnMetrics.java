package com.javaclaw.chat;

/** 单轮消息计量，不包含会话累计值。 */
public record TurnMetrics(
        long inputTokens,
        long cacheReadInputTokens,
        long cacheWriteInputTokens,
        long outputTokens,
        long reasoningTokens,
        long modelCalls,
        long durationMs) {
    public static final TurnMetrics ZERO = new TurnMetrics(0, 0, 0, 0, 0, 0, 0);

    public TurnMetrics {
        inputTokens = Math.max(0, inputTokens);
        cacheReadInputTokens = Math.min(inputTokens, Math.max(0, cacheReadInputTokens));
        cacheWriteInputTokens = Math.min(
                inputTokens - cacheReadInputTokens, Math.max(0, cacheWriteInputTokens));
        outputTokens = Math.max(0, outputTokens);
        reasoningTokens = Math.min(outputTokens, Math.max(0, reasoningTokens));
        modelCalls = Math.max(0, modelCalls);
        durationMs = Math.max(0, durationMs);
    }

    public TurnMetrics(long inputTokens, long outputTokens, long durationMs) {
        this(inputTokens, 0, 0, outputTokens, 0,
                inputTokens > 0 || outputTokens > 0 ? 1 : 0, durationMs);
    }

    public long totalTokens() {
        return inputTokens + outputTokens;
    }
}
