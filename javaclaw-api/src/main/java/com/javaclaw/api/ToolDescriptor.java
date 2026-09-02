package com.javaclaw.api;

import java.util.Objects;
import java.util.Set;

/**
 * 模型可发现但不可直接执行的工具描述。
 *
 * @param identity 工具身份
 * @param description 简洁功能说明
 * @param inputSchema 规范化 JSON Schema
 * @param outputSchema 规范化 JSON Schema
 * @param risk 最低风险等级
 * @param tags 搜索标签
 */
public record ToolDescriptor(
        ToolIdentity identity,
        String description,
        CanonicalPayload inputSchema,
        CanonicalPayload outputSchema,
        ToolRisk risk,
        Set<String> tags) {
    /** 校验描述与 Schema，并复制标签。 */
    public ToolDescriptor {
        Objects.requireNonNull(identity, "identity");
        description = Preconditions.text(description, "description");
        Objects.requireNonNull(inputSchema, "inputSchema");
        Objects.requireNonNull(outputSchema, "outputSchema");
        Objects.requireNonNull(risk, "risk");
        tags = tags.stream()
                .map(value -> Preconditions.text(value, "tag").toLowerCase())
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }
}
