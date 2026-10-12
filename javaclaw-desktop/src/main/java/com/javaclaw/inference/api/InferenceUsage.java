package com.javaclaw.inference.api;

/** 精确的模型用量；本地推理不会用字符数近似 token。 */
public record InferenceUsage(long promptTokens, long completionTokens) {

    public InferenceUsage {
        if (promptTokens < 0 || completionTokens < 0) {
            throw new IllegalArgumentException("token 用量不能为负数");
        }
    }

    public long totalTokens() {
        return Math.addExact(promptTokens, completionTokens);
    }
}
