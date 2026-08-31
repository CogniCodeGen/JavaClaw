package com.javaclaw.sdk.model;

import java.util.Objects;

/**
 * Immutable canonical JSON carried as text so the public SDK never exposes Jackson types.
 *
 * @param canonicalJson 非空白 JSON 文本；构造器只检查文本非空，JSON 解析由内部 Mapper 完成
 */
public record JsonDocument(String canonicalJson) {
    public static final JsonDocument EMPTY_OBJECT = new JsonDocument("{}");

    /** 去除外层空白并拒绝空文本；不在 SDK 公共模型中引入 Jackson，也不声称已完成 Schema 校验。 */
    public JsonDocument {
        canonicalJson = Objects.requireNonNull(canonicalJson, "canonicalJson").strip();
        if (canonicalJson.isEmpty()) {
            throw new IllegalArgumentException("canonicalJson is empty");
        }
    }
}
