package com.javaclaw.framework.spi;

import com.fasterxml.jackson.databind.JsonNode;

public interface StateCodec {
    String extensionId();

    int schemaVersion();

    JsonNode encode(Object state);

    Object decode(JsonNode json);
}
