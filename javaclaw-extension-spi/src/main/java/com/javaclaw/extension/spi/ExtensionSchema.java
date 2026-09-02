package com.javaclaw.extension.spi;

import java.util.Objects;

import com.javaclaw.api.CanonicalPayload;

/**
 * 扩展公开的版本化 JSON Schema。
 *
 * @param schemaId 全局唯一 schema 标识
 * @param schema JSON Schema 文档
 */
public record ExtensionSchema(String schemaId, CanonicalPayload schema) {
    /** 校验标识和文档。 */
    public ExtensionSchema {
        schemaId = Objects.requireNonNull(schemaId, "schemaId").strip();
        if (schemaId.isEmpty()) {
            throw new IllegalArgumentException("schemaId must not be blank");
        }
        Objects.requireNonNull(schema, "schema");
    }
}
