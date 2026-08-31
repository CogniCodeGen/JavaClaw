package com.javaclaw.server.transport;

import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;

/** One protocol-facing domain handler. */
interface RpcHandler extends AutoCloseable {
    Set<String> methods();

    JsonNode handle(String method, JsonNode params);

    @Override
    default void close() {}
}
