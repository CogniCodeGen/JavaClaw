package com.javaclaw.sdk;

import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import com.fasterxml.jackson.databind.JsonNode;

import com.javaclaw.protocol.JsonRpcCodec;

/** Transport abstraction kept inside the SDK; clients never depend on the App Server runtime. */
interface RpcConnection extends AutoCloseable {
    CompletableFuture<JsonNode> request(String method, JsonNode params);

    void notify(String method, JsonNode params);

    AutoCloseable onNotification(Consumer<ServerNotification> listener);

    JsonRpcCodec codec();

    default AutoCloseable onConnectionState(Consumer<ConnectionStatus> listener) {
        return () -> {};
    }

    default AutoCloseable onRecoveredThread(Consumer<ProtocolRecovery> listener) {
        return () -> {};
    }

    @Override
    void close();
}
