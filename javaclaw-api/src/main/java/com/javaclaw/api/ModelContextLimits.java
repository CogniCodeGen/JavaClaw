package com.javaclaw.api;

import java.util.Objects;
import java.util.OptionalLong;

/**
 * 精确 Provider 模型版本的窗口元数据；空值表示未知，不是无限容量。
 *
 * @param provider 不可变 Provider/model 引用
 * @param contextWindowTokens 输入与生成共享的窗口 token 容量，可未知
 * @param maximumOutputTokens 单次生成 token 上限，可未知
 */
public record ModelContextLimits(
        ProviderRef provider, OptionalLong contextWindowTokens, OptionalLong maximumOutputTokens) {
    /** 校验容量；平台 fallback 不写回用户模型元数据。 */
    public ModelContextLimits {
        Objects.requireNonNull(provider, "provider");
        Objects.requireNonNull(contextWindowTokens, "contextWindowTokens");
        Objects.requireNonNull(maximumOutputTokens, "maximumOutputTokens");
        if (contextWindowTokens.isPresent() && contextWindowTokens.getAsLong() < 4
                || maximumOutputTokens.isPresent() && maximumOutputTokens.getAsLong() < 1) {
            throw new IllegalArgumentException("model context limits must be positive");
        }
        if (contextWindowTokens.isPresent()
                && maximumOutputTokens.isPresent()
                && maximumOutputTokens.getAsLong() > contextWindowTokens.getAsLong()) {
            throw new IllegalArgumentException("output limit exceeds context window");
        }
    }

    /**
     * 创建未声明容量的元数据。
     *
     * @param provider 精确 Provider/model
     * @return 保留 unknown 的元数据
     */
    public static ModelContextLimits unknown(ProviderRef provider) {
        return new ModelContextLimits(provider, OptionalLong.empty(), OptionalLong.empty());
    }
}
