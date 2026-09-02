package com.javaclaw.runtime;

/**
 * 从父 Turn 预留给直接子 Thread 的 token。
 *
 * @param inputTokens 输入 token
 * @param outputTokens 输出 token
 */
public record ReservedChildBudget(long inputTokens, long outputTokens) {
    /** 校验预留量。 */
    public ReservedChildBudget {
        if (inputTokens < 1 || outputTokens < 1) {
            throw new IllegalArgumentException("child token reservations must be positive");
        }
    }
}
