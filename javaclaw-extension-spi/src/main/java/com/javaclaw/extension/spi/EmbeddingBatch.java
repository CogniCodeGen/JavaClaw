package com.javaclaw.extension.spi;

import java.util.List;
import java.util.Objects;

/**
 * 一次嵌入调用的不可变结果。
 *
 * @param fingerprint 精确 Provider、revision、模型与维度的 SHA-256 指纹
 * @param dimensions 向量维度
 * @param vectors 与输入文本顺序一致的向量
 */
public record EmbeddingBatch(String fingerprint, int dimensions, List<EmbeddingVector> vectors) {
    /** 校验指纹、维度和向量一致性。 */
    public EmbeddingBatch {
        fingerprint = Objects.requireNonNull(fingerprint, "fingerprint").strip().toLowerCase(java.util.Locale.ROOT);
        if (!fingerprint.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("fingerprint must be SHA-256 hex");
        }
        if (dimensions < 1 || dimensions > 65_536) {
            throw new IllegalArgumentException("dimensions must be between 1 and 65536");
        }
        vectors = List.copyOf(Objects.requireNonNull(vectors, "vectors"));
        if (vectors.isEmpty()
                || vectors.stream().anyMatch(vector -> vector.values().size() != dimensions)) {
            throw new IllegalArgumentException("vectors must be non-empty and match dimensions");
        }
    }
}
