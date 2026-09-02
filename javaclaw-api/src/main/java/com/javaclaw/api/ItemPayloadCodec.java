package com.javaclaw.api;

/**
 * 在强类型 payload 与规范化 JSON 之间转换。
 *
 * @param <T> codec 处理的 payload 类型
 */
public interface ItemPayloadCodec<T extends ItemPayload> {
    /**
     * 返回全局唯一 schema 标识。
     *
     * @return schema 标识
     */
    String schemaId();

    /**
     * 返回可编码的 Java 类型。
     *
     * @return payload 类型
     */
    Class<T> payloadType();

    /**
     * 编码并规范化 payload。
     *
     * @param payload 强类型内容
     * @return 规范化 JSON
     */
    CanonicalPayload encode(T payload);

    /**
     * 解码已验证的 payload。
     *
     * @param payload 规范化 JSON
     * @return 强类型内容
     */
    T decode(CanonicalPayload payload);
}
