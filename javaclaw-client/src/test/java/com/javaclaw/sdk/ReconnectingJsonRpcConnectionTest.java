package com.javaclaw.sdk;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.channels.Channels;
import java.nio.channels.Pipe;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import com.javaclaw.protocol.JsonRpcCodec;
import com.javaclaw.protocol.JsonRpcFrame;
import com.javaclaw.protocol.JsonRpcNotification;
import com.javaclaw.protocol.JsonRpcRequest;
import com.javaclaw.protocol.JsonRpcResponse;
import com.javaclaw.protocol.RpcMethods;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReconnectingJsonRpcConnectionTest {
    @Test
    void reconnectsReinitializesAndOnlyReplaysAnIdempotentAmbiguousRequest() throws Exception {
        AtomicInteger connections = new AtomicInteger();
        AtomicInteger replayedMutations = new AtomicInteger();
        List<ConnectionStatus.State> states = new CopyOnWriteArrayList<>();
        try (ReconnectingJsonRpcConnection connection = new ReconnectingJsonRpcConnection(() -> {
                    int attempt = connections.incrementAndGet();
                    Pipe requests = Pipe.open();
                    Pipe responses = Pipe.open();
                    var serverInput = Channels.newInputStream(requests.source());
                    var clientOutput = Channels.newOutputStream(requests.sink());
                    var clientInput = Channels.newInputStream(responses.source());
                    var serverOutput = Channels.newOutputStream(responses.sink());
                    Thread.startVirtualThread(
                            () -> serveAttempt(attempt, serverInput, serverOutput, replayedMutations));
                    return new JsonRpcConnection(clientInput, clientOutput);
                });
                AutoCloseable ignored = connection.onConnectionState(status -> states.add(status.state()))) {
            ObjectNode initialize = JsonNodeFactory.instance.objectNode();
            initialize.put("protocolVersion", 1);
            initialize
                    .putObject("clientInfo")
                    .put("name", "test")
                    .put("title", "test")
                    .put("version", "1");
            initialize.putObject("capabilities");
            connection.request(RpcMethods.INITIALIZE, initialize).get(2, TimeUnit.SECONDS);
            connection.notify(RpcMethods.INITIALIZED, JsonNodeFactory.instance.objectNode());

            ObjectNode mutation = JsonNodeFactory.instance.objectNode();
            mutation.put("idempotencyKey", "stable-key");
            mutation.put("value", "safe-to-replay");
            assertEquals(
                    "persisted",
                    connection
                            .request(RpcMethods.MEMORY_PUT, mutation)
                            .get(5, TimeUnit.SECONDS)
                            .path("result")
                            .asText());
            assertEquals(2, connections.get());
            assertEquals(1, replayedMutations.get());
            assertTrue(states.contains(ConnectionStatus.State.RECONNECTING));
            assertEquals(ConnectionStatus.State.CONNECTED, states.getLast());
        }
    }

    private static void serveAttempt(
            int attempt, java.io.InputStream input, java.io.OutputStream output, AtomicInteger replayedMutations) {
        JsonRpcCodec codec = new JsonRpcCodec();
        try (input;
                output;
                BufferedReader lines = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8));
                BufferedWriter responses = new BufferedWriter(new OutputStreamWriter(output, StandardCharsets.UTF_8))) {
            String line;
            while ((line = lines.readLine()) != null) {
                JsonRpcFrame frame = codec.decode(line);
                if (frame instanceof JsonRpcNotification) {
                    continue;
                }
                if (!(frame instanceof JsonRpcRequest request)) {
                    continue;
                }
                if (RpcMethods.INITIALIZE.equals(request.method())) {
                    ObjectNode result = JsonNodeFactory.instance.objectNode();
                    result.put("protocolVersion", 1);
                    result.put("serverName", "test");
                    result.put("serverVersion", "4");
                    result.putObject("capabilities");
                    result.put("connectionId", "connection-" + attempt);
                    write(codec, responses, JsonRpcResponse.success(request.id(), result));
                } else if (RpcMethods.MEMORY_PUT.equals(request.method())) {
                    if (attempt == 1) {
                        return;
                    } // The server may have committed before EOF.
                    replayedMutations.incrementAndGet();
                    ObjectNode result = JsonNodeFactory.instance.objectNode();
                    result.put("result", "persisted");
                    write(codec, responses, JsonRpcResponse.success(request.id(), result));
                }
            }
        } catch (Exception ignored) {
            // Closing either side is the failure injection used by this test.
        }
    }

    private static void write(JsonRpcCodec codec, BufferedWriter output, JsonRpcResponse response) throws Exception {
        output.write(codec.encode(response));
        output.newLine();
        output.flush();
    }
}
