package com.javaclaw.framework.extension;

import com.fasterxml.jackson.databind.JsonNode;
import com.javaclaw.framework.spi.EventCodec;
import com.javaclaw.framework.spi.EventTypeDescriptor;

import java.util.Objects;

/** Event schema and codec owned by one exact extension artifact. */
public record EventTypeRegistration(
        String extensionId,
        EventTypeDescriptor descriptor,
        EventCodec<?> codec) {

    public EventTypeRegistration {
        extensionId = Objects.requireNonNull(extensionId, "extensionId");
        descriptor = Objects.requireNonNull(descriptor, "descriptor");
        codec = Objects.requireNonNull(codec, "codec");
    }

    /** Decode then encode to enforce the registered versioned wire representation. */
    public JsonNode normalize(JsonNode payload) {
        return normalize(codec, Objects.requireNonNull(payload, "payload"));
    }

    private static <T> JsonNode normalize(EventCodec<T> codec, JsonNode payload) {
        return Objects.requireNonNull(codec.encode(codec.decode(payload)), "encoded payload")
                .deepCopy();
    }
}
