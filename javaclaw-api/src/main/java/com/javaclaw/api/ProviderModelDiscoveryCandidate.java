package com.javaclaw.api;

import java.util.Objects;
import java.util.OptionalInt;
import java.util.Set;

/**
 * Provider 目录返回的模型候选；用途只是厂商元数据建议，保存前仍需用户确认。
 *
 * @param modelId Provider 原生模型标识
 * @param displayName 用户可见名称
 * @param suggestedPurposes 厂商元数据能够确认的建议用途；未知时为空
 * @param embeddingDimensions 厂商声明的 Embedding 输出维度；未知时为空
 */
public record ProviderModelDiscoveryCandidate(
        String modelId,
        String displayName,
        Set<ProviderModelPurpose> suggestedPurposes,
        OptionalInt embeddingDimensions) {
    /** 复制建议用途并校验返回值。 */
    public ProviderModelDiscoveryCandidate {
        modelId = Preconditions.boundedText(modelId, "modelId", 1_000);
        displayName = Preconditions.boundedText(displayName, "displayName", 1_000);
        suggestedPurposes = Set.copyOf(Objects.requireNonNull(suggestedPurposes, "suggestedPurposes"));
        embeddingDimensions = Objects.requireNonNull(embeddingDimensions, "embeddingDimensions");
        if (embeddingDimensions.isPresent()) {
            int dimensions = embeddingDimensions.getAsInt();
            if (!suggestedPurposes.contains(ProviderModelPurpose.EMBEDDING)) {
                throw new IllegalArgumentException("embeddingDimensions requires the suggested EMBEDDING purpose");
            }
            if (dimensions < 1 || dimensions > 65_536) {
                throw new IllegalArgumentException("embeddingDimensions must be between 1 and 65536");
            }
        }
    }
}
