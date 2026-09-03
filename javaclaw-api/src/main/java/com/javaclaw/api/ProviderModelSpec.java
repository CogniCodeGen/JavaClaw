package com.javaclaw.api;

import java.util.Objects;
import java.util.OptionalInt;
import java.util.Set;

/**
 * Provider 端点中一个可用模型的强类型配置。
 *
 * @param modelId Provider 原生模型标识
 * @param displayName 用户可见名称
 * @param purposes 模型用途；至少包含一项
 * @param embeddingDimensions Embedding 输出维度；使用 Provider 默认值时为空
 */
public record ProviderModelSpec(
        String modelId, String displayName, Set<ProviderModelPurpose> purposes, OptionalInt embeddingDimensions) {
    /** 复制用途并校验模型配置。 */
    public ProviderModelSpec {
        modelId = Preconditions.boundedText(modelId, "modelId", 1_000);
        displayName = Preconditions.boundedText(displayName, "displayName", 1_000);
        purposes = Set.copyOf(Objects.requireNonNull(purposes, "purposes"));
        if (purposes.isEmpty()) {
            throw new IllegalArgumentException("model purposes must not be empty");
        }
        embeddingDimensions = Objects.requireNonNull(embeddingDimensions, "embeddingDimensions");
        if (embeddingDimensions.isPresent()) {
            int dimensions = embeddingDimensions.getAsInt();
            if (!purposes.contains(ProviderModelPurpose.EMBEDDING)) {
                throw new IllegalArgumentException("embeddingDimensions requires the EMBEDDING purpose");
            }
            if (dimensions < 1 || dimensions > 65_536) {
                throw new IllegalArgumentException("embeddingDimensions must be between 1 and 65536");
            }
        }
    }

    /**
     * 判断模型是否支持指定用途。
     *
     * @param purpose 用途
     * @return 声明该用途时为 true
     */
    public boolean supports(ProviderModelPurpose purpose) {
        return purposes.contains(Objects.requireNonNull(purpose, "purpose"));
    }
}
