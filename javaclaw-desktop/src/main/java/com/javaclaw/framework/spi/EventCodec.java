package com.javaclaw.framework.spi;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Objects;

/** Version-specific, deterministic codec for an extension event payload. */
public interface EventCodec<T> {
    JsonNode encode(T payload);

    T decode(JsonNode payload);

    /** Identity codec for extensions whose public payload model is already JSON. */
    static EventCodec<JsonNode> jsonNode() {
        return JsonNodeEventCodec.INSTANCE;
    }

    final class JsonNodeEventCodec implements EventCodec<JsonNode> {
        private static final JsonNodeEventCodec INSTANCE = new JsonNodeEventCodec();

        private JsonNodeEventCodec() {}

        @Override
        public JsonNode encode(JsonNode payload) {
            return Objects.requireNonNull(payload, "payload").deepCopy();
        }

        @Override
        public JsonNode decode(JsonNode payload) {
            return Objects.requireNonNull(payload, "payload").deepCopy();
        }
    }
}
