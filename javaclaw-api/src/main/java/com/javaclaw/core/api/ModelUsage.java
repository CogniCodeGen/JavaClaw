package com.javaclaw.core.api;

/**
 * 单次模型调用的 token 计数；未知计数使用零，不允许负值。
 *
 * @param inputTokens 输入 token 数，非负
 * @param outputTokens 输出 token 数，非负
 * @param reasoningTokens Provider 报告的推理 token 数，非负
 */
public record ModelUsage(long inputTokens, long outputTokens, long reasoningTokens) {
    /** 拒绝负数计数，保持预算统计和审计用量的一致性。 */
    public ModelUsage {
        if (inputTokens < 0 || outputTokens < 0 || reasoningTokens < 0) {
            throw new IllegalArgumentException("token usage must be non-negative");
        }
    }

    public static final ModelUsage ZERO = new ModelUsage(0, 0, 0);
}
