package com.javaclaw.runtime;

/**
 * 一次或多次模型调用的 token 使用量。
 *
 * @param inputTokens 输入 token
 * @param outputTokens 可见输出 token
 * @param reasoningTokens 推理 token
 * @param cachedInputTokens 命中缓存的输入 token，包含于 inputTokens
 */
public record ModelUsage(long inputTokens, long outputTokens, long reasoningTokens, long cachedInputTokens) {
    /** 校验计数。 */
    public ModelUsage {
        if (inputTokens < 0 || outputTokens < 0 || reasoningTokens < 0 || cachedInputTokens < 0) {
            throw new IllegalArgumentException("token counts must not be negative");
        }
        if (cachedInputTokens > inputTokens) {
            throw new IllegalArgumentException("cachedInputTokens must not exceed inputTokens");
        }
    }

    /**
     * 返回全零 usage。
     *
     * @return 空 usage
     */
    public static ModelUsage zero() {
        return new ModelUsage(0, 0, 0, 0);
    }

    /**
     * 合并两次独立调用。
     *
     * @param other 另一份 usage
     * @return 合计
     */
    public ModelUsage plus(ModelUsage other) {
        return new ModelUsage(
                Math.addExact(inputTokens, other.inputTokens),
                Math.addExact(outputTokens, other.outputTokens),
                Math.addExact(reasoningTokens, other.reasoningTokens),
                Math.addExact(cachedInputTokens, other.cachedInputTokens));
    }
}
