package com.javaclaw.chat;

/** 单轮消息计量，不包含会话累计值。 */
public record TurnMetrics(long inputTokens, long outputTokens, long durationMs) {
    public TurnMetrics {
        inputTokens = Math.max(0, inputTokens);
        outputTokens = Math.max(0, outputTokens);
        durationMs = Math.max(0, durationMs);
    }

    public long totalTokens() {
        return inputTokens + outputTokens;
    }
}
