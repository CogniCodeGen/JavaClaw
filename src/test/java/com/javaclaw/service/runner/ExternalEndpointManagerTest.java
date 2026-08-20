package com.javaclaw.service.runner;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.javaclaw.service.api.ExternalEndpointDescriptor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.net.InetSocketAddress;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class ExternalEndpointManagerTest {
    private static final String API_KEY = "test-key-0123456789-abcdefghijkl";
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    @AfterEach
    void closeExecutor() {
        executor.shutdownNow();
    }

    @Test
    void httpRequiresBearerAndRoutesThroughRunnerOwnedListener() throws Exception {
        assumeLoopbackSocketsAvailable();
        ServicePluginWire.Endpoint config = endpoint("http", "HTTP", "127.0.0.1");
        AtomicReference<String> credential = new AtomicReference<>();
        try (ExternalEndpointManager manager = manager(config)) {
            manager.register(new ExternalEndpointDescriptor("http",
                    ExternalEndpointDescriptor.Protocol.HTTP, "/", Set.of("echo")), invocation -> {
                credential.set(invocation.credentialId());
                invocation.response().send(200, "application/octet-stream", Map.of(), invocation.body());
            });
            int port = port(manager, "http");
            HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

            HttpResponse<byte[]> unauthorized = client.send(HttpRequest.newBuilder(
                            URI.create("http://127.0.0.1:" + port + "/echo"))
                    .POST(HttpRequest.BodyPublishers.ofByteArray(new byte[]{1})).build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            assertEquals(401, unauthorized.statusCode());

            byte[] body = "socket-boundary".getBytes(StandardCharsets.UTF_8);
            HttpResponse<byte[]> response = client.send(HttpRequest.newBuilder(
                            URI.create("http://127.0.0.1:" + port + "/echo"))
                    .header("Authorization", "Bearer " + API_KEY)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body)).build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            assertEquals(200, response.statusCode());
            assertArrayEquals(body, response.body());
            assertEquals(API_KEY.substring(0, 12), credential.get());
        }
    }

    @Test
    void tcpUsesFramedAuthenticatedRequestsAndResponses() throws Exception {
        assumeLoopbackSocketsAvailable();
        ServicePluginWire.Endpoint config = endpoint("tcp", "TCP", "127.0.0.1");
        try (ExternalEndpointManager manager = manager(config)) {
            manager.register(new ExternalEndpointDescriptor("tcp",
                    ExternalEndpointDescriptor.Protocol.TCP, "/rpc", Set.of("echo")), invocation ->
                    invocation.response().send(201, "application/octet-stream", Map.of(), invocation.body()));
            int port = port(manager, "tcp");
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress("127.0.0.1", port), 2_000);
                socket.setSoTimeout(2_000);
                DataOutputStream output = new DataOutputStream(socket.getOutputStream());
                DataInputStream input = new DataInputStream(socket.getInputStream());
                writeFrame(output, API_KEY.getBytes(StandardCharsets.UTF_8));
                byte[] body = new byte[]{4, 3, 2, 1};
                writeFrame(output, body);

                assertEquals(201, input.readInt());
                byte[] contentType = input.readNBytes(input.readInt());
                assertEquals("application/octet-stream", new String(contentType, StandardCharsets.UTF_8));
                assertArrayEquals(body, input.readNBytes(input.readInt()));
            }
        }
    }

    @Test
    void wildcardBindIsRejectedBeforeListenerStarts() {
        ServicePluginWire.Endpoint config = endpoint("unsafe", "HTTP", "0.0.0.0");
        try (ExternalEndpointManager manager = manager(config)) {
            assertThrows(IllegalStateException.class, () -> manager.register(
                    new ExternalEndpointDescriptor("unsafe",
                            ExternalEndpointDescriptor.Protocol.HTTP, "/", Set.of()),
                    invocation -> { }));
        }
    }

    @Test
    void rejectsUnapprovedDuplicateAndInvalidEndpointDefinitions() {
        ServicePluginWire.Endpoint http = endpoint("http", "HTTP", "127.0.0.1");
        assertThrows(IllegalArgumentException.class, () -> new ExternalEndpointManager(
                List.of(http, http), executor, new ObjectMapper(), new RunnerLogger("test")));

        try (ExternalEndpointManager manager = manager(http)) {
            assertThrows(IllegalStateException.class, () -> manager.register(
                    new ExternalEndpointDescriptor("missing",
                            ExternalEndpointDescriptor.Protocol.HTTP, "/", Set.of()),
                    invocation -> { }));
            assertThrows(IllegalArgumentException.class, () -> manager.register(
                    new ExternalEndpointDescriptor("http",
                            ExternalEndpointDescriptor.Protocol.TCP, "/", Set.of()),
                    invocation -> { }));
        }

        ServicePluginWire.Endpoint websocket = endpoint(
                "websocket", "WEBSOCKET", "127.0.0.1");
        try (ExternalEndpointManager manager = manager(websocket)) {
            assertThrows(IllegalStateException.class, () -> manager.register(
                    new ExternalEndpointDescriptor("websocket",
                            ExternalEndpointDescriptor.Protocol.WEBSOCKET, "/", Set.of()),
                    invocation -> { }));
        }

        for (String protocol : List.of("HTTP", "TCP")) {
            ServicePluginWire.Endpoint weak = new ServicePluginWire.Endpoint(
                    "weak", protocol, "127.0.0.1", 0, false, false,
                    "", "", "short", 60, 100_000, 1, 4, 1024);
            try (ExternalEndpointManager manager = manager(weak)) {
                assertThrows(IllegalStateException.class, () -> manager.register(
                        new ExternalEndpointDescriptor("weak",
                                ExternalEndpointDescriptor.Protocol.valueOf(protocol), "/", Set.of()),
                        invocation -> { }));
            }
        }

        ServicePluginWire.Endpoint publicAddress = endpoint("public", "HTTP", "8.8.8.8");
        try (ExternalEndpointManager manager = manager(publicAddress)) {
            assertThrows(IllegalStateException.class, () -> manager.register(
                    new ExternalEndpointDescriptor("public",
                            ExternalEndpointDescriptor.Protocol.HTTP, "/", Set.of()),
                    invocation -> { }));
        }

        ServicePluginWire.Endpoint insecureLan = endpoint("lan", "HTTP", "192.168.1.20");
        try (ExternalEndpointManager manager = manager(insecureLan)) {
            assertThrows(IllegalStateException.class, () -> manager.register(
                    new ExternalEndpointDescriptor("lan",
                            ExternalEndpointDescriptor.Protocol.HTTP, "/", Set.of()),
                    invocation -> { }));
        }

        ServicePluginWire.Endpoint missingTls = new ServicePluginWire.Endpoint(
                "https", "HTTPS", "127.0.0.1", 0, true, false,
                "", null, API_KEY, 60, 100_000, 1, 4, 1024);
        try (ExternalEndpointManager manager = manager(missingTls)) {
            assertThrows(IllegalStateException.class, () -> manager.register(
                    new ExternalEndpointDescriptor("https",
                            ExternalEndpointDescriptor.Protocol.HTTPS, "/", Set.of()),
                    invocation -> { }));
        }
    }

    @Test
    void registrationIsUniqueAndCloseIsIdempotent() throws Exception {
        assumeLoopbackSocketsAvailable();
        ServicePluginWire.Endpoint config = endpoint("http", "HTTP", "127.0.0.1");
        try (ExternalEndpointManager manager = manager(config)) {
            var descriptor = new ExternalEndpointDescriptor("http",
                    ExternalEndpointDescriptor.Protocol.HTTP, "/", Set.of());
            var registration = manager.register(descriptor, invocation ->
                    invocation.response().send(204, null, null, null));
            assertThrows(IllegalStateException.class,
                    () -> manager.register(descriptor, invocation -> { }));
            registration.close();
            registration.close();
            assertTrue(manager.snapshot().isEmpty());
            manager.stopAccepting();
            manager.stopAccepting();
        }
    }

    @Test
    void hotEndpointConfigurationRequiresAStoppedListenerAndAppliesNewCredentials()
            throws Exception {
        assumeLoopbackSocketsAvailable();
        ServicePluginWire.Endpoint initial = endpoint("http", "HTTP", "127.0.0.1");
        String replacementKey = "replacement-key-0123456789-abcdef";
        ServicePluginWire.Endpoint replacement = new ServicePluginWire.Endpoint(
                "http", "HTTP", "127.0.0.1", 0, false, false,
                "", "", replacementKey, 60, 100_000, 1, 4, 1024 * 1024);
        try (ExternalEndpointManager manager = manager(initial)) {
            var descriptor = new ExternalEndpointDescriptor("http",
                    ExternalEndpointDescriptor.Protocol.HTTP, "/", Set.of());
            var registration = manager.register(descriptor, invocation ->
                    invocation.response().send(204, null, Map.of(), null));
            assertThrows(IllegalStateException.class,
                    () -> manager.reconfigure(List.of(replacement)));
            registration.close();

            manager.reconfigure(List.of(replacement));
            manager.register(descriptor, invocation ->
                    invocation.response().send(204, null, Map.of(), null));
            int port = port(manager, "http");
            HttpClient client = HttpClient.newHttpClient();
            assertEquals(401, send(client, port, "/", API_KEY, new byte[0]).statusCode());
            assertEquals(204, send(client, port, "/", replacementKey, new byte[0]).statusCode());
        }
    }

    @Test
    void httpReturnsBoundedErrorsAndSupportsOwnedStreaming() throws Exception {
        assumeLoopbackSocketsAvailable();
        ServicePluginWire.Endpoint config = new ServicePluginWire.Endpoint(
                "http", "HTTP", "127.0.0.1", 0, false, false,
                "", "", API_KEY, 60, 100_000, 1, 4, 4);
        AtomicBoolean metadataObserved = new AtomicBoolean();
        try (ExternalEndpointManager manager = manager(config)) {
            manager.register(new ExternalEndpointDescriptor("http",
                    ExternalEndpointDescriptor.Protocol.HTTP, "/", Set.of()), invocation -> {
                switch (invocation.path()) {
                    case "/missing" -> { }
                    case "/failure" -> throw new IllegalStateException("synthetic endpoint failure");
                    case "/stream" -> {
                        metadataObserved.set(!invocation.requestId().isBlank()
                                && "POST".equals(invocation.method())
                                && invocation.headers().containsKey("Authorization")
                                && invocation.remoteAddress() != null
                                && !invocation.cancellation().isCancellationRequested());
                        invocation.response().startStream(200, "text/plain",
                                Map.of("X-Fixture", "yes", "Connection", "ignored"));
                        invocation.response().stream("ok".getBytes(StandardCharsets.UTF_8));
                        invocation.response().closeStream();
                    }
                    default -> invocation.response().send(
                            200, "text/plain", Map.of(), invocation.body());
                }
            });
            int port = port(manager, "http");
            HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

            assertEquals(401, send(client, port, "/missing", "wrong-key", new byte[0]).statusCode());
            assertEquals(500, send(client, port, "/missing", API_KEY, new byte[0]).statusCode());
            assertEquals(500, send(client, port, "/failure", API_KEY, new byte[0]).statusCode());
            assertEquals(400, send(client, port, "/echo", API_KEY, new byte[5]).statusCode());
            HttpResponse<byte[]> streamed = send(client, port, "/stream", API_KEY, new byte[0]);
            assertEquals(200, streamed.statusCode());
            assertArrayEquals("ok".getBytes(StandardCharsets.UTF_8), streamed.body());
            assertEquals("yes", streamed.headers().firstValue("X-Fixture").orElseThrow());
            assertFalse(streamed.headers().firstValue("Connection").orElse("").equals("ignored"));
            assertTrue(metadataObserved.get());
        }
    }

    @Test
    void tcpRejectsWrongCredentialAndInvalidFrames() throws Exception {
        assumeLoopbackSocketsAvailable();
        ServicePluginWire.Endpoint config = endpoint("tcp", "TCP", "127.0.0.1");
        try (ExternalEndpointManager manager = manager(config)) {
            manager.register(new ExternalEndpointDescriptor("tcp",
                    ExternalEndpointDescriptor.Protocol.TCP, "/rpc", Set.of()), invocation -> { });
            int port = port(manager, "tcp");
            try (Socket socket = connected(port)) {
                DataOutputStream output = new DataOutputStream(socket.getOutputStream());
                DataInputStream input = new DataInputStream(socket.getInputStream());
                writeFrame(output, "wrong-key".getBytes(StandardCharsets.UTF_8));
                assertEquals(401, input.readInt());
            }
            try (Socket socket = connected(port)) {
                DataOutputStream output = new DataOutputStream(socket.getOutputStream());
                writeFrame(output, API_KEY.getBytes(StandardCharsets.UTF_8));
                output.writeInt(-1);
                output.flush();
            }
        }
    }

    @RepeatedTest(3)
    void httpTimeoutReturns504PropagatesCancellationAndReleasesLeaseOnce() throws Exception {
        assumeLoopbackSocketsAvailable();
        ServicePluginWire.Endpoint config = new ServicePluginWire.Endpoint(
                "http", "HTTP", "127.0.0.1", 0, false, false,
                "", "", API_KEY, 60, 100_000, 1, 4, 1024, 1);
        AtomicBoolean cancellationObserved = new AtomicBoolean();
        try (ExternalEndpointManager manager = manager(config)) {
            manager.register(new ExternalEndpointDescriptor("http",
                    ExternalEndpointDescriptor.Protocol.HTTP, "/", Set.of()), invocation -> {
                try {
                    while (!invocation.cancellation().isCancellationRequested()) {
                        Thread.sleep(25);
                    }
                    cancellationObserved.set(true);
                } catch (InterruptedException interrupted) {
                    cancellationObserved.set(invocation.cancellation().isCancellationRequested());
                    Thread.currentThread().interrupt();
                }
            });
            int port = port(manager, "http");
            HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

            HttpResponse<byte[]> response = send(client, port, "/timeout", API_KEY, new byte[0]);

            assertEquals(504, response.statusCode());
            assertTrue(new String(response.body(), StandardCharsets.UTF_8)
                    .contains("request_timeout"));
            long cancellationDeadline = System.nanoTime() + Duration.ofSeconds(1).toNanos();
            while (!cancellationObserved.get() && System.nanoTime() < cancellationDeadline) {
                Thread.sleep(10);
            }
            assertTrue(cancellationObserved.get());
            assertEquals(0, manager.snapshot().getFirst().get("connections"));
        }
    }

    @Test
    void committedSseTimeoutEndsWithOneSseErrorAndDoneInsteadOfJson() throws Exception {
        assumeLoopbackSocketsAvailable();
        ServicePluginWire.Endpoint config = new ServicePluginWire.Endpoint(
                "http", "SSE", "127.0.0.1", 0, false, false,
                "", "", API_KEY, 60, 100_000, 1, 4, 1024, 1);
        try (ExternalEndpointManager manager = manager(config)) {
            manager.register(new ExternalEndpointDescriptor("http",
                    ExternalEndpointDescriptor.Protocol.SSE, "/", Set.of()), invocation -> {
                invocation.response().startStream(200, "text/event-stream", Map.of());
                try {
                    while (!invocation.cancellation().isCancellationRequested()) Thread.sleep(25);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
                invocation.response().stream(("data: {\"error\":{\"code\":\"request_timeout\"},"
                        + "\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":2}}\n\n")
                        .getBytes(StandardCharsets.UTF_8));
                invocation.response().stream("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));
                invocation.response().closeStream();
            });
            int port = port(manager, "http");
            HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

            HttpResponse<byte[]> response = send(client, port, "/stream", API_KEY, new byte[0]);
            String body = new String(response.body(), StandardCharsets.UTF_8);

            assertEquals(200, response.statusCode());
            assertTrue(body.contains("request_timeout"));
            assertEquals(1, body.split("data: \\[DONE]", -1).length - 1);
            assertFalse(body.contains("internal_error"));
        }
    }

    private ExternalEndpointManager manager(ServicePluginWire.Endpoint endpoint) {
        return new ExternalEndpointManager(List.of(endpoint), executor,
                new ObjectMapper(), new RunnerLogger("test"));
    }

    private static ServicePluginWire.Endpoint endpoint(String id, String protocol, String address) {
        return new ServicePluginWire.Endpoint(id, protocol, address, 0, false,
                false, "", "", API_KEY, 60, 100_000, 1, 4, 1024 * 1024);
    }

    private static int port(ExternalEndpointManager manager, String id) {
        return manager.snapshot().stream().filter(value -> id.equals(value.get("id")))
                .map(value -> ((Number) value.get("port")).intValue()).findFirst().orElseThrow();
    }

    private static void writeFrame(DataOutputStream output, byte[] value) throws Exception {
        output.writeInt(value.length);
        output.write(value);
        output.flush();
    }

    private static Socket connected(int port) throws Exception {
        Socket socket = new Socket();
        socket.connect(new InetSocketAddress("127.0.0.1", port), 2_000);
        socket.setSoTimeout(2_000);
        return socket;
    }

    private static HttpResponse<byte[]> send(HttpClient client, int port, String path,
                                             String apiKey, byte[] body) throws Exception {
        return client.send(HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + port + path))
                .header("Authorization", "Bearer " + apiKey)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body)).build(),
                HttpResponse.BodyHandlers.ofByteArray());
    }

    private static void assumeLoopbackSocketsAvailable() {
        try (ServerSocket probe = new ServerSocket()) {
            probe.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
        } catch (Exception unavailable) {
            assumeTrue(false, "当前测试环境禁止回环 Socket: " + unavailable.getMessage());
        }
    }
}
