package com.javaclaw.extension.spi;

import com.javaclaw.api.CanonicalPayload;

/** 扩展契约与规范 JSON payload 之间的共享编解码端口。 */
public interface ExtensionPayloadCodec {
    /**
     * 编码强类型契约。
     *
     * @param value 契约值
     * @return 规范 JSON object
     */
    CanonicalPayload encode(Object value);

    /**
     * 解码强类型契约。
     *
     * @param payload 规范 JSON object
     * @param type 目标类型
     * @param <T> 目标类型
     * @return 解码值
     */
    <T> T decode(CanonicalPayload payload, Class<T> type);
}
