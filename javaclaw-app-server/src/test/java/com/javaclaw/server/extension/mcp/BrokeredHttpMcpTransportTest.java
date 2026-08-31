package com.javaclaw.server.extension.mcp;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import com.javaclaw.sandbox.api.BrokerExchange;
import com.javaclaw.sandbox.api.BrokerRequest;
import com.javaclaw.sandbox.api.BrokerResponse;
import com.javaclaw.sandbox.api.BrokerResponseHead;
import com.javaclaw.sandbox.api.NetworkBroker;
import com.javaclaw.sandbox.api.NetworkPolicy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BrokeredHttpMcpTransportTest {
    private static final URI ENDPOINT = URI.create("https://mcp.example.test/rpc");
    private static final NetworkPolicy POLICY =
            new NetworkPolicy(NetworkPolicy.Mode.ALLOWLIST, java.util.Set.of("mcp.example.test"));
    private final ObjectMapper json = new ObjectMapper();
    private final McpCodec codec = new McpCodec(json);

    @Test
    void sendsModernMetadataAndAcceptsJsonResponse() throws Exception {
        AtomicReference<BrokerRequest> seen = new AtomicReference<>();
        NetworkBroker broker = (request, policy) -> {
            seen.set(request);
            ObjectNode call = (ObjectNode) json.readTree(request.body());
            ObjectNode response = response(call);
            response.putObject("result").put("resultType", "complete").put("ok", true);
            return new BrokerResponse(
                    200,
                    ENDPOINT,
                    Map.of("content-type", List.of("application/json; charset=utf-8")),
                    json.writeValueAsBytes(response),
                    0);
        };
        ObjectNode request =
                codec.request(McpProtocol.TOOLS_CALL, json.createObjectNode().put("name", "lookup"));
        try (BrokeredHttpMcpTransport transport = transport(broker)) {
            var result = transport.exchange(
                    request,
                    McpHttpRoutingHeaders.forRequest(request, null, null),
                    Duration.ofSeconds(1),
                    ignored -> {});
            assertTrue(result.path("result").path("ok").asBoolean());
        }
        assertEquals("POST", seen.get().method());
        assertEquals(McpProtocol.VERSION, seen.get().headers().get("MCP-Protocol-Version"));
        assertEquals(McpProtocol.TOOLS_CALL, seen.get().headers().get("Mcp-Method"));
        assertEquals("lookup", seen.get().headers().get("Mcp-Name"));
        assertEquals("application/json, text/event-stream", seen.get().headers().get("Accept"));
    }

    @Test
    void readsRequestScopedSseNotificationsBeforeTheFinalResponse() throws Exception {
        NetworkBroker broker = (request, policy) -> {
            ObjectNode call = (ObjectNode) json.readTree(request.body());
            ObjectNode notification =
                    json.createObjectNode().put("jsonrpc", "2.0").put("method", "notifications/progress");
            notification.putObject("params").put("progress", 0.5);
            ObjectNode response = response(call);
            response.putObject("result").put("resultType", "complete");
            String sse = ": keepalive\n"
                    + "data: " + json.writeValueAsString(notification) + "\n\n"
                    + "data: " + json.writeValueAsString(response) + "\n\n";
            return new BrokerResponse(
                    200,
                    ENDPOINT,
                    Map.of("content-type", List.of("text/event-stream")),
                    sse.getBytes(StandardCharsets.UTF_8),
                    0);
        };
        AtomicReference<String> notification = new AtomicReference<>();
        ObjectNode request = codec.request(
                McpProtocol.RESOURCES_READ, json.createObjectNode().put("uri", "file:///readme"));
        try (BrokeredHttpMcpTransport transport = transport(broker)) {
            var result = transport.exchange(
                    request,
                    McpHttpRoutingHeaders.forRequest(request, null, null),
                    Duration.ofSeconds(1),
                    value -> notification.set(value.path("method").asText()));
            assertEquals("complete", result.path("result").path("resultType").asText());
        }
        assertEquals("notifications/progress", notification.get());
    }

    @Test
    void cancellationClosesTheActiveHttpExchange() throws Exception {
        CountDownLatch reading = new CountDownLatch(1);
        AtomicBoolean cancelled = new AtomicBoolean();
        NetworkBroker broker = new NetworkBroker() {
            @Override
            public BrokerResponse execute(BrokerRequest request, NetworkPolicy policy) {
                throw new AssertionError("streaming path required");
            }

            @Override
            public BrokerExchange openExchange(BrokerRequest request, NetworkPolicy policy) {
                return new BrokerExchange() {
                    @Override
                    public BrokerResponseHead head() {
                        return new BrokerResponseHead(
                                200, ENDPOINT, Map.of("content-type", List.of("text/event-stream")), 0);
                    }

                    @Override
                    public int read(byte[] destination, int offset, int length) throws IOException {
                        reading.countDown();
                        while (!cancelled.get()) {
                            java.util.concurrent.locks.LockSupport.parkNanos(1_000_000);
                        }
                        throw new IOException("cancelled");
                    }

                    @Override
                    public void cancel() {
                        cancelled.set(true);
                    }

                    @Override
                    public boolean cancelled() {
                        return cancelled.get();
                    }

                    @Override
                    public void close() {
                        cancelled.set(true);
                    }
                };
            }
        };
        ObjectNode params = json.createObjectNode();
        params.putObject("notifications").put("toolsListChanged", true);
        ObjectNode request = codec.request(McpProtocol.SUBSCRIPTIONS_LISTEN, params);
        try (BrokeredHttpMcpTransport transport = transport(broker);
                var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var future = executor.submit(() -> transport.exchange(
                    request,
                    McpHttpRoutingHeaders.forRequest(request, null, null),
                    Duration.ofSeconds(1),
                    ignored -> {}));
            assertTrue(reading.await(1, TimeUnit.SECONDS));
            transport.cancel(request.get("id"), "test");
            assertThrows(ExecutionException.class, () -> future.get(1, TimeUnit.SECONDS));
        }
        assertTrue(cancelled.get());
    }

    private BrokeredHttpMcpTransport transport(NetworkBroker broker) {
        return new BrokeredHttpMcpTransport(ENDPOINT, broker, POLICY, McpHttpAuthorization.NONE, json, codec);
    }

    private ObjectNode response(ObjectNode request) {
        ObjectNode response = json.createObjectNode().put("jsonrpc", "2.0");
        response.set("id", request.get("id").deepCopy());
        return response;
    }
}
