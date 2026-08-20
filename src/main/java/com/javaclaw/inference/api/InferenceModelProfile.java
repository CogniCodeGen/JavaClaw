package com.javaclaw.inference.api;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** 可加载的模型档案；档案把不可变资产与一个版本化运行时绑定。 */
public record InferenceModelProfile(
        UUID id,
        String name,
        Kind kind,
        UUID assetId,
        String runtimeId,
        Map<String, Object> loadParameters,
        Map<String, Object> defaultParameters,
        int contextLength,
        int embeddingDimensions,
        State state,
        String failure,
        Instant createdAt,
        Instant updatedAt) {

    public InferenceModelProfile {
        if (id == null) throw new IllegalArgumentException("档案 ID 不能为空");
        name = requireText(name, "档案名称");
        if (kind == null) throw new IllegalArgumentException("模型类型不能为空");
        if (assetId == null) throw new IllegalArgumentException("资产 ID 不能为空");
        runtimeId = requireText(runtimeId, "运行时 ID");
        loadParameters = loadParameters == null ? Map.of() : Map.copyOf(loadParameters);
        defaultParameters = defaultParameters == null ? Map.of() : Map.copyOf(defaultParameters);
        if (contextLength <= 0) throw new IllegalArgumentException("上下文上限必须大于零");
        if (kind == Kind.EMBEDDING && embeddingDimensions <= 0) {
            throw new IllegalArgumentException("嵌入档案必须提供实际维度");
        }
        if (state == null) throw new IllegalArgumentException("档案状态不能为空");
        failure = failure == null ? "" : failure.strip();
        createdAt = createdAt == null ? Instant.now() : createdAt;
        updatedAt = updatedAt == null ? createdAt : updatedAt;
    }

    public enum Kind { GENERATION, EMBEDDING }
    public enum State { DRAFT, VERIFYING, READY, FAILED, DISABLED }

    private static String requireText(String value, String label) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(label + "不能为空");
        return value.strip();
    }
}
