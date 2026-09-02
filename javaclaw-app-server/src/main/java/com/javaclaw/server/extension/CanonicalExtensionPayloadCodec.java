package com.javaclaw.server.extension;

import java.util.Objects;

import com.javaclaw.api.CanonicalPayload;
import com.javaclaw.extension.spi.ExtensionPayloadCodec;
import com.javaclaw.protocol.CanonicalJson;

/** 把 Protocol 的唯一 CanonicalJson 实现适配到 Extension SPI。 */
public final class CanonicalExtensionPayloadCodec implements ExtensionPayloadCodec {
    private final CanonicalJson json;

    /**
     * 创建适配器。
     *
     * @param json 共享 JSON codec
     */
    public CanonicalExtensionPayloadCodec(CanonicalJson json) {
        this.json = Objects.requireNonNull(json, "json");
    }

    @Override
    public CanonicalPayload encode(Object value) {
        return json.encode(value);
    }

    @Override
    public <T> T decode(CanonicalPayload payload, Class<T> type) {
        return json.decode(payload, type);
    }
}
