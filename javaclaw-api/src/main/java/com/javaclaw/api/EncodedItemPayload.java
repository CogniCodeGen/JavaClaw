package com.javaclaw.api;

import java.util.Objects;

/**
 * 编码后的 Item 内容。
 *
 * @param schemaId schema 标识
 * @param payload 规范化 JSON
 */
public record EncodedItemPayload(String schemaId, CanonicalPayload payload) {
    /** 校验 schema 与内容。 */
    public EncodedItemPayload {
        schemaId = Preconditions.text(schemaId, "schemaId");
        Objects.requireNonNull(payload, "payload");
    }
}
