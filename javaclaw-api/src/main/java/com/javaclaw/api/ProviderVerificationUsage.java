package com.javaclaw.api;

/**
 * Provider 验证返回的脱敏 token 使用量。
 *
 * @param inputTokens 输入 token
 * @param outputTokens 可见输出 token
 * @param reasoningTokens 推理 token
 * @param cachedInputTokens 已缓存输入 token，包含于 inputTokens
 */
public record ProviderVerificationUsage(
        long inputTokens, long outputTokens, long reasoningTokens, long cachedInputTokens) {
    /** 校验所有计数均为非负数。 */
    public ProviderVerificationUsage {
        if (inputTokens < 0 || outputTokens < 0 || reasoningTokens < 0 || cachedInputTokens < 0) {
            throw new IllegalArgumentException("token counts must not be negative");
        }
        if (cachedInputTokens > inputTokens) {
            throw new IllegalArgumentException("cachedInputTokens must not exceed inputTokens");
        }
    }
}
