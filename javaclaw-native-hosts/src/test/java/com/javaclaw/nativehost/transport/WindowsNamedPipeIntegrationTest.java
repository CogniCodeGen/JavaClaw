package com.javaclaw.nativehost.transport;

import java.nio.channels.AsynchronousCloseException;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import com.javaclaw.api.CanonicalPayload;
import com.javaclaw.protocol.JsonRpcCodec;
import com.javaclaw.protocol.JsonRpcRequest;
import com.javaclaw.protocol.JsonRpcResponse;
import com.javaclaw.protocol.RpcId;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledOnOs(OS.WINDOWS)
class WindowsNamedPipeIntegrationTest {
    @Test
    void exchangesFramedProtocolMessagesAndOwnsBothHandles() throws Exception {
        WindowsPipeName name = uniqueName();
        AtomicReference<Throwable> serverFailure = new AtomicReference<>();
        try (WindowsNamedPipeRpcServer server = WindowsNamedPipeRpcServer.bind(name)) {
            Thread serving = Thread.ofVirtual().start(() -> {
                try (var connection = server.accept(new JsonRpcCodec())) {
                    JsonRpcRequest request = assertInstanceOf(JsonRpcRequest.class, connection.receive());
                    connection.send(JsonRpcResponse.success(request.id(), new CanonicalPayload("{\"ok\":true}")));
                } catch (Throwable failure) {
                    serverFailure.set(failure);
                }
            });

            try (var connection = new WindowsNamedPipeTransport(name, Duration.ofSeconds(2)).connect()) {
                connection.send(new JsonRpcRequest(new RpcId("one"), "diagnostics/read", new CanonicalPayload("{}")));
                JsonRpcResponse response = assertInstanceOf(JsonRpcResponse.class, connection.receive());
                assertEquals("{\"ok\":true}", response.result().orElseThrow().json());
            }
            serving.join();
            assertNull(serverFailure.get());
        }
    }

    @Test
    void closeWakesPendingAcceptAndIsIdempotent() throws Exception {
        WindowsPipeName name = uniqueName();
        AtomicReference<Throwable> result = new AtomicReference<>();
        WindowsNamedPipeRpcServer server = WindowsNamedPipeRpcServer.bind(name);
        Thread accepting = Thread.ofVirtual().start(() -> {
            try {
                server.accept(new JsonRpcCodec());
            } catch (Throwable failure) {
                result.set(failure);
            }
        });

        long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
        while (accepting.getState() != Thread.State.WAITING && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
        server.close();
        server.close();
        accepting.join(Duration.ofSeconds(2));

        assertTrue(result.get() instanceof AsynchronousCloseException, () -> String.valueOf(result.get()));
    }

    private static WindowsPipeName uniqueName() {
        return WindowsPipeName.parse(
                "javaclaw-test-" + UUID.randomUUID().toString().replace("-", ""));
    }
}
