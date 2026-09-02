package com.javaclaw.extension.spi;

import java.util.List;
import java.util.Objects;

/**
 * 可安全编码为规范 JSON 的嵌入向量。
 *
 * @param values 有限双精度分量；至少一维
 */
public record EmbeddingVector(List<Double> values) {
    /** 复制并校验所有分量。 */
    public EmbeddingVector {
        values = List.copyOf(Objects.requireNonNull(values, "values"));
        if (values.isEmpty() || values.size() > 65_536) {
            throw new IllegalArgumentException("embedding vector size is invalid");
        }
        if (values.stream().anyMatch(value -> value == null || !Double.isFinite(value))) {
            throw new IllegalArgumentException("embedding vector must contain finite values");
        }
    }
}
