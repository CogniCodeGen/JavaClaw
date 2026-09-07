package com.javaclaw.runtime;

import java.util.Objects;

/**
 * Turn 冻结的上下文运行政策；容量与累计费用预算分别约束每次请求和整个 Turn。
 *
 * @param windowTokens 工作窗口 token 上限；fallback 不表示 Provider 声明的真实容量
 * @param maximumOutputTokens 单次生成 token 上限
 * @param source 容量来源，不能来自模型输出
 * @param estimatorVersion 确定性估算器版本
 */
public record ModelContextPolicy(long windowTokens, long maximumOutputTokens, String source, String estimatorVersion) {
    /** 校验有限正数容量与来源。 */
    public ModelContextPolicy {
        if (windowTokens < 4 || maximumOutputTokens < 1 || maximumOutputTokens > windowTokens) {
            throw new IllegalArgumentException("invalid model context limits");
        }
        source = Objects.requireNonNull(source, "source");
        estimatorVersion = Objects.requireNonNull(estimatorVersion, "estimatorVersion");
        if (source.isBlank() || !ContextTokenEstimator.VERSION.equals(estimatorVersion)) {
            throw new IllegalArgumentException("unsupported context policy version");
        }
    }

    /** @return 未知模型容量时的平台工作上限，不伪造 Provider 元数据 */
    public static ModelContextPolicy fallback() {
        return new ModelContextPolicy(32_768, 4_096, "PLATFORM_FALLBACK_V1", ContextTokenEstimator.VERSION);
    }

    /**
     * 计算保留输入空间后的本次生成上限。
     *
     * @param remainingOutput 剩余累计输出额度
     * @return 非负单次上限
     */
    public long outputAllowance(long remainingOutput) {
        return Math.max(0, Math.min(remainingOutput, Math.min(maximumOutputTokens, windowTokens / 4)));
    }

    /**
     * 计算预留生成空间和估算误差后的输入容量。
     *
     * @param outputAllowance 本次生成上限
     * @return 非负输入容量
     */
    public long inputCapacity(long outputAllowance) {
        long margin = Math.min(
                4_096, Math.max(1, Math.min(windowTokens / 4, windowTokens / 20 + (windowTokens % 20 == 0 ? 0 : 1))));
        return Math.max(0, windowTokens - outputAllowance - margin);
    }
}
