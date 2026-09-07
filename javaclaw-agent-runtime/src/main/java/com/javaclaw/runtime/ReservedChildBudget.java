package com.javaclaw.runtime;

/**
 * 从父 Turn 预留给直接子 Thread 的 token。
 *
 * @param inputTokens 输入 token
 * @param outputTokens 输出 token
 * @param toolCalls 子任务可消耗的工具次数，必须从父级预留
 */
public record ReservedChildBudget(long inputTokens, long outputTokens, int toolCalls) {
    /** 校验预留量。 */
    public ReservedChildBudget {
        if (inputTokens < 1 || outputTokens < 1 || toolCalls < 0) {
            throw new IllegalArgumentException("child token reservations must be positive");
        }
    }

    /**
     * 预留纯模型子任务，不分配工具次数。
     *
     * @param inputTokens 输入 token
     * @param outputTokens 输出 token
     */
    public ReservedChildBudget(long inputTokens, long outputTokens) {
        this(inputTokens, outputTokens, 0);
    }
}
