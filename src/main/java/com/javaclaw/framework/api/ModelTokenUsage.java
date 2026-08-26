package com.javaclaw.framework.api;

/**
 * Provider-neutral usage for one or more model calls. Cache and reasoning values are
 * subsets of input/output respectively and are never added again to total tokens.
 */
public record ModelTokenUsage(
        long inputTokens,
        long cacheReadInputTokens,
        long cacheWriteInputTokens,
        long outputTokens,
        long reasoningTokens,
        long modelCalls) {
    public static final ModelTokenUsage ZERO = new ModelTokenUsage(0, 0, 0, 0, 0, 0);

    public ModelTokenUsage {
        inputTokens = Math.max(0, inputTokens);
        cacheReadInputTokens = Math.min(inputTokens, Math.max(0, cacheReadInputTokens));
        cacheWriteInputTokens = Math.min(
                inputTokens - cacheReadInputTokens, Math.max(0, cacheWriteInputTokens));
        outputTokens = Math.max(0, outputTokens);
        reasoningTokens = Math.min(outputTokens, Math.max(0, reasoningTokens));
        modelCalls = Math.max(0, modelCalls);
    }

    public ModelTokenUsage(long inputTokens, long outputTokens) {
        this(inputTokens, 0, 0, outputTokens, 0,
                inputTokens > 0 || outputTokens > 0 ? 1 : 0);
    }

    public ModelTokenUsage plus(ModelTokenUsage other) {
        if (other == null) return this;
        return new ModelTokenUsage(
                Math.addExact(inputTokens, other.inputTokens),
                Math.addExact(cacheReadInputTokens, other.cacheReadInputTokens),
                Math.addExact(cacheWriteInputTokens, other.cacheWriteInputTokens),
                Math.addExact(outputTokens, other.outputTokens),
                Math.addExact(reasoningTokens, other.reasoningTokens),
                Math.addExact(modelCalls, other.modelCalls));
    }

    public long totalTokens() { return Math.addExact(inputTokens, outputTokens); }

    public double cacheHitRate() {
        return inputTokens == 0 ? -1 : (double) cacheReadInputTokens / inputTokens;
    }
}
