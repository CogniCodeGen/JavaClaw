package com.javaclaw.api;

import java.time.Duration;
import java.util.Objects;

/**
 * Turn 启动时冻结的资源预算。
 *
 * @param inputTokens 最大输入 token
 * @param outputTokens 最大输出 token
 * @param toolCalls 最大工具调用次数
 * @param childThreads 最大直接子 Thread 数，平台上限为 4
 * @param wallTime 最大墙钟时间
 */
public record TurnBudget(long inputTokens, long outputTokens, int toolCalls, int childThreads, Duration wallTime) {
    /** 校验所有预算为有限正数。 */
    public TurnBudget {
        inputTokens = Preconditions.positive(inputTokens, "inputTokens");
        outputTokens = Preconditions.positive(outputTokens, "outputTokens");
        if (toolCalls < 1) {
            throw new IllegalArgumentException("toolCalls must be positive");
        }
        if (childThreads < 0 || childThreads > 4) {
            throw new IllegalArgumentException("childThreads must be between 0 and 4");
        }
        wallTime = Objects.requireNonNull(wallTime, "wallTime");
        if (wallTime.isZero() || wallTime.isNegative()) {
            throw new IllegalArgumentException("wallTime must be positive");
        }
    }
}
