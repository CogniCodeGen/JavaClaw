package com.javaclaw.api;

import java.util.Objects;

/** 解码结果；未知 schema 必须保留原始 payload，不能静默丢字段。 */
public sealed interface DecodedItemPayload permits DecodedItemPayload.Known, DecodedItemPayload.Unknown {
    /**
     * 已由注册 codec 解码的内容。
     *
     * @param schemaId schema 标识
     * @param value 强类型内容
     */
    record Known(String schemaId, ItemPayload value) implements DecodedItemPayload {
        /** 校验 schema 与内容。 */
        public Known {
            schemaId = Preconditions.text(schemaId, "schemaId");
            Objects.requireNonNull(value, "value");
        }
    }

    /**
     * 当前客户端不认识的内容。
     *
     * @param schemaId schema 标识
     * @param payload 未修改的规范化 JSON
     */
    record Unknown(String schemaId, CanonicalPayload payload) implements DecodedItemPayload {
        /** 校验 schema 与原始内容。 */
        public Unknown {
            schemaId = Preconditions.text(schemaId, "schemaId");
            Objects.requireNonNull(payload, "payload");
        }
    }
}
