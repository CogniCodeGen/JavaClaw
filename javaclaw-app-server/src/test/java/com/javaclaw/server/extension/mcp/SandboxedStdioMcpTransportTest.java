package com.javaclaw.server.extension.mcp;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import com.javaclaw.sandbox.api.SandboxSession;
import com.javaclaw.sandbox.api.SandboxSessionFrame;
import com.javaclaw.sandbox.api.SandboxSignal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SandboxedStdioMcpTransportTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void validatesNotificationsInOrderBeforeCompletingTheResponse() throws Exception {
        CountDownLatch callbackStarted = new CountDownLatch(1);
        CountDownLatch releaseCallback = new CountDownLatch(1);
        FakeSession session = new FakeSession(request -> {
            ObjectNode acknowledged = notification("notifications/subscriptions/acknowledged");
            acknowledged
                    .withObject("/params")
                    .withObject("/_meta")
                    .set(
                            "io.modelcontextprotocol/subscriptionId",
                            request.get("id").deepCopy());
            sessionFrame(sessionRef.get(), acknowledged);
            sessionFrame(sessionRef.get(), response(request));
        });
        sessionRef.set(session);

        try (SandboxedStdioMcpTransport transport = new SandboxedStdioMcpTransport(session, json, new McpCodec(json))) {
            ObjectNode request = new McpCodec(json).request(McpProtocol.SUBSCRIPTIONS_LISTEN, json.createObjectNode());
            CompletableFuture<JsonNode> result = CompletableFuture.supplyAsync(() -> {
                try {
                    return transport.exchange(request, Map.of(), Duration.ofSeconds(2), value -> {
                        callbackStarted.countDown();
                        try {
                            if (!releaseCallback.await(1, TimeUnit.SECONDS)) {
                                throw new IllegalStateException("test callback timed out");
                            }
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                            throw new IllegalStateException(interrupted);
                        }
                    });
                } catch (Exception failure) {
                    throw new CompletionException(failure);
                }
            });

            assertTrue(callbackStarted.await(1, TimeUnit.SECONDS));
            assertFalse(result.isDone(), "response must wait for notification validation");
            releaseCallback.countDown();
            assertEquals(request.get("id"), result.get(1, TimeUnit.SECONDS).get("id"));
        }
    }

    @Test
    void routesProgressOnlyByTheDeclaredToken() throws Exception {
        AtomicReference<JsonNode> delivered = new AtomicReference<>();
        FakeSession session = new FakeSession(request -> {
            ObjectNode progress = notification(McpProtocol.PROGRESS);
            progress.withObject("/params")
                    .put("progressToken", "progress-1")
                    .put("progress", 1)
                    .put("total", 2);
            sessionFrame(sessionRef.get(), progress);
            sessionFrame(sessionRef.get(), response(request));
        });
        sessionRef.set(session);

        try (SandboxedStdioMcpTransport transport = new SandboxedStdioMcpTransport(session, json, new McpCodec(json))) {
            ObjectNode params = json.createObjectNode();
            params.putObject("_meta").put("progressToken", "progress-1");
            ObjectNode request = new McpCodec(json).request(McpProtocol.TOOLS_CALL, params);

            transport.exchange(request, Map.of(), Duration.ofSeconds(2), delivered::set);

            assertEquals(McpProtocol.PROGRESS, delivered.get().path("method").asText());
        }
    }

    @Test
    void serverCancellationTerminatesTheCorrelatedRequest() throws Exception {
        FakeSession session = new FakeSession(request -> {
            ObjectNode cancelled = notification(McpProtocol.CANCELLED);
            cancelled.withObject("/params").set("requestId", request.get("id").deepCopy());
            cancelled.withObject("/params").put("reason", "disabled");
            sessionFrame(sessionRef.get(), cancelled);
        });
        sessionRef.set(session);

        try (SandboxedStdioMcpTransport transport = new SandboxedStdioMcpTransport(session, json, new McpCodec(json))) {
            ObjectNode request = new McpCodec(json).request(McpProtocol.TOOLS_CALL, json.createObjectNode());

            IOException failure = assertThrows(
                    IOException.class,
                    () -> transport.exchange(request, Map.of(), Duration.ofSeconds(2), ignored -> {}));

            assertTrue(failure.getMessage().contains("disabled"));
        }
    }

    private final AtomicReference<FakeSession> sessionRef = new AtomicReference<>();

    private ObjectNode notification(String method) {
        ObjectNode value = json.createObjectNode().put("jsonrpc", "2.0").put("method", method);
        value.putObject("params");
        return value;
    }

    private ObjectNode response(ObjectNode request) {
        ObjectNode response = json.createObjectNode().put("jsonrpc", "2.0");
        response.set("id", request.get("id").deepCopy());
        response.putObject("result").put("resultType", "complete");
        return response;
    }

    private static void sessionFrame(FakeSession session, JsonNode value) throws Exception {
        session.emit((value.toString() + "\n").getBytes(StandardCharsets.UTF_8));
    }

    @FunctionalInterface
    private interface OnWrite {
        void accept(ObjectNode request) throws Exception;
    }

    private final class FakeSession implements SandboxSession {
        private final BlockingQueue<SandboxSessionFrame> frames = new LinkedBlockingQueue<>();
        private final OnWrite onWrite;
        private final AtomicBoolean alive = new AtomicBoolean(true);

        private FakeSession(OnWrite onWrite) {
            this.onWrite = onWrite;
        }

        @Override
        public String id() {
            return "mcp-test";
        }

        @Override
        public SandboxSessionFrame read(Duration timeout) throws InterruptedException {
            return frames.poll(Math.max(1, timeout.toMillis()), TimeUnit.MILLISECONDS);
        }

        @Override
        public void write(byte[] input) throws Exception {
            onWrite.accept((ObjectNode) json.readTree(input));
        }

        void emit(byte[] line) {
            frames.add(SandboxSessionFrame.stream("nonce", SandboxSessionFrame.Kind.STDOUT, line));
        }

        @Override
        public void closeInput() {}

        @Override
        public void resize(int columns, int rows) {}

        @Override
        public void signal(SandboxSignal signal) {}

        @Override
        public boolean isAlive() {
            return alive.get();
        }

        @Override
        public void terminate() {
            alive.set(false);
        }
    }
}
