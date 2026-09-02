package com.javaclaw.server.mcp;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import com.javaclaw.api.CancellationSource;
import com.javaclaw.api.CanonicalPayload;
import com.javaclaw.api.McpAuthType;
import com.javaclaw.api.McpEndpoint;
import com.javaclaw.api.McpEndpointSpec;
import com.javaclaw.api.McpEndpointState;
import com.javaclaw.api.McpProtocol;
import com.javaclaw.api.McpSamplingRole;
import com.javaclaw.api.McpTransport;
import com.javaclaw.api.WorkspaceId;
import com.javaclaw.extension.spi.McpClientInteractionPort;
import com.javaclaw.protocol.CanonicalJson;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class McpStdioSessionTest {
    private static final Instant NOW = Instant.parse("2026-09-01T00:00:00Z");

    @Test
    void 签名Stdio会话只允许受治理Elicitation与Sampling() throws Exception {
        CanonicalJson json = new CanonicalJson();
        ScriptedProcess process = new ScriptedProcess(peer -> serveInteractionFlow(peer, json));
        AtomicInteger elicited = new AtomicInteger();
        AtomicInteger sampled = new AtomicInteger();
        McpClientInteractionPort interactions = interactions(json, elicited, sampled);

        try (McpStdioSession session = new McpStdioSession(
                process, endpoint(), interactions, new CancellationSource(), json, Clock.fixed(NOW, ZoneOffset.UTC))) {
            assertEquals(McpProtocol.VERSION, session.initialize(true).protocolVersion());
            CanonicalPayload result = session.call("tools/call", json.parse("{\"arguments\":{},\"name\":\"lookup\"}"));
            assertEquals("done", json.textField(result, "status").orElseThrow());
        }

        process.assertSucceeded();
        assertEquals(1, elicited.get());
        assertEquals(1, sampled.get());
    }

    @Test
    void 不声明交互且远端协议不匹配时不发送Initialized通知() throws Exception {
        CanonicalJson json = new CanonicalJson();
        ScriptedProcess process = new ScriptedProcess(peer -> {
            CanonicalPayload initialize = peer.read(json);
            CanonicalPayload capabilities = json.objectField(initialize, "params")
                    .flatMap(params -> json.objectField(params, "capabilities"))
                    .orElseThrow();
            assertTrue(json.fieldNames(capabilities).isEmpty());
            peer.write(json, response("javaclaw-stdio-1", Map.of("protocolVersion", "2025-11-25")));
        });

        try (McpStdioSession session = new McpStdioSession(
                process,
                endpoint(),
                decliningInteractions(),
                new CancellationSource(),
                json,
                Clock.fixed(NOW, ZoneOffset.UTC))) {
            var initialized = session.initialize(false);
            assertEquals("2025-11-25", initialized.protocolVersion());
            assertTrue(initialized.capabilities().isEmpty());
        }
        process.assertSucceeded();
    }

    @Test
    void 未知反向方法和非法参数返回JsonRpc错误而拒绝交互返回安全结果() throws Exception {
        CanonicalJson json = new CanonicalJson();
        AtomicInteger assistantMessages = new AtomicInteger();
        ScriptedProcess process = new ScriptedProcess(peer -> serveRejectedInteractionFlow(peer, json));
        McpClientInteractionPort interactions = new McpClientInteractionPort() {
            @Override
            public Optional<CanonicalPayload> elicit(
                    com.javaclaw.api.McpElicitationRequest request, com.javaclaw.api.CancellationToken cancellation) {
                return Optional.empty();
            }

            @Override
            public Optional<CanonicalPayload> sample(
                    com.javaclaw.api.McpSamplingRequest request, com.javaclaw.api.CancellationToken cancellation) {
                assertEquals(
                        McpSamplingRole.ASSISTANT, request.messages().getFirst().role());
                assistantMessages.incrementAndGet();
                return Optional.empty();
            }
        };

        try (McpStdioSession session = new McpStdioSession(
                process, endpoint(), interactions, new CancellationSource(), json, Clock.fixed(NOW, ZoneOffset.UTC))) {
            CanonicalPayload result = session.call("tools/call", json.parse("{}"));
            assertEquals("done", json.textField(result, "status").orElseThrow());
        }
        process.assertSucceeded();
        assertEquals(1, assistantMessages.get());
    }

    @Test
    void Stdio响应严格校验匹配Id错误对象与Result() throws Exception {
        assertCallFailure(response("other-id", Map.of()), IllegalArgumentException.class);
        assertCallFailure(
                Map.of("jsonrpc", "2.0", "id", "javaclaw-stdio-1", "error", Map.of("code", -1)),
                IllegalStateException.class);
        assertCallFailure(Map.of("jsonrpc", "2.0", "id", "javaclaw-stdio-1"), IllegalArgumentException.class);
    }

    @Test
    void 反向通知超过固定上限时终止调用() throws Exception {
        CanonicalJson json = new CanonicalJson();
        StringBuilder responses = new StringBuilder();
        for (int index = 0; index < 17; index++) {
            responses.append(json.encode(Map.of("jsonrpc", "2.0", "method", "notifications/progress"))
                    .json());
            responses.append('\n');
        }
        StaticInputProcess process = new StaticInputProcess(responses.toString());

        try (McpStdioSession session = new McpStdioSession(
                process,
                endpoint(),
                decliningInteractions(),
                new CancellationSource(),
                json,
                Clock.fixed(NOW, ZoneOffset.UTC))) {
            assertThrows(IllegalStateException.class, () -> session.call("tools/call", json.parse("{}")));
        }
    }

    @Test
    void 请求响应大小关闭管道与总超时均按边界失败() throws Exception {
        CanonicalJson json = new CanonicalJson();
        ScriptedProcess oversizedRequest = new ScriptedProcess(peer -> {});
        try (McpStdioSession session = new McpStdioSession(
                oversizedRequest,
                endpoint(),
                decliningInteractions(),
                new CancellationSource(),
                json,
                Clock.fixed(NOW, ZoneOffset.UTC))) {
            CanonicalPayload huge = json.encode(Map.of("value", "x".repeat(4 * 1024 * 1024)));
            assertThrows(IOException.class, () -> session.call("tools/call", huge));
        }
        oversizedRequest.assertSucceeded();

        ScriptedProcess oversizedResponse = new ScriptedProcess(peer -> {
            peer.read(json);
            peer.output().write("x".repeat(4 * 1024 * 1024 + 1));
            peer.output().newLine();
            peer.output().flush();
        });
        try (McpStdioSession session = new McpStdioSession(
                oversizedResponse,
                endpoint(),
                decliningInteractions(),
                new CancellationSource(),
                json,
                Clock.fixed(NOW, ZoneOffset.UTC))) {
            assertThrows(IOException.class, () -> session.call("tools/call", json.parse("{}")));
        }
        oversizedResponse.assertSucceeded();

        ScriptedProcess incomplete = new ScriptedProcess(peer -> peer.read(json));
        try (McpStdioSession session = new McpStdioSession(
                incomplete,
                endpoint(),
                decliningInteractions(),
                new CancellationSource(),
                json,
                Clock.fixed(NOW, ZoneOffset.UTC))) {
            assertThrows(IOException.class, () -> session.call("tools/call", json.parse("{}")));
        }
        incomplete.assertSucceeded();

        ScriptedProcess timedOut = new ScriptedProcess(peer -> {
            peer.read(json);
            Thread.sleep(500);
        });
        try (McpStdioSession session = new McpStdioSession(
                timedOut,
                endpoint(Duration.ofMillis(100)),
                decliningInteractions(),
                new CancellationSource(),
                json,
                Clock.fixed(NOW, ZoneOffset.UTC))) {
            assertThrows(
                    java.util.concurrent.TimeoutException.class, () -> session.call("tools/call", json.parse("{}")));
        }
    }

    private static void serveRejectedInteractionFlow(Peer peer, CanonicalJson json) throws Exception {
        peer.read(json);
        peer.write(json, Map.of("jsonrpc", "2.0", "id", "unknown-1", "method", "resources/read"));
        assertEquals(-32601L, errorCode(peer.read(json), json));

        peer.write(json, request("invalid-1", "elicitation/create", Map.of("message", "缺少 Schema")));
        assertEquals(-32602L, errorCode(peer.read(json), json));

        peer.write(
                json,
                request(
                        "decline-1",
                        "elicitation/create",
                        Map.of(
                                "message",
                                "是否继续",
                                "requestedSchema",
                                Map.of(
                                        "type",
                                        "object",
                                        "properties",
                                        Map.of("confirmed", Map.of("type", "boolean")),
                                        "additionalProperties",
                                        false))));
        CanonicalPayload declined = json.objectField(peer.read(json), "result").orElseThrow();
        assertEquals("decline", json.textField(declined, "action").orElseThrow());

        peer.write(
                json,
                request(
                        "sample-1",
                        "sampling/createMessage",
                        Map.of(
                                "maxTokens",
                                16,
                                "messages",
                                java.util.List.of(Map.of(
                                        "role", "assistant", "content", Map.of("type", "text", "text", "external"))))));
        CanonicalPayload sampled = json.objectField(peer.read(json), "result").orElseThrow();
        assertEquals("declined", json.textField(sampled, "stopReason").orElseThrow());
        peer.write(json, response("javaclaw-stdio-1", Map.of("status", "done")));
    }

    private static long errorCode(CanonicalPayload envelope, CanonicalJson json) {
        return json.objectField(envelope, "error")
                .flatMap(error -> json.integerField(error, "code"))
                .orElseThrow();
    }

    private static void assertCallFailure(Map<String, Object> response, Class<? extends Throwable> expected)
            throws Exception {
        CanonicalJson json = new CanonicalJson();
        ScriptedProcess process = new ScriptedProcess(peer -> {
            peer.read(json);
            peer.write(json, response);
        });
        try (McpStdioSession session = new McpStdioSession(
                process,
                endpoint(),
                decliningInteractions(),
                new CancellationSource(),
                json,
                Clock.fixed(NOW, ZoneOffset.UTC))) {
            assertThrows(expected, () -> session.call("tools/call", json.parse("{}")));
        }
        process.assertSucceeded();
    }

    private static McpClientInteractionPort decliningInteractions() {
        return new McpClientInteractionPort() {
            @Override
            public Optional<CanonicalPayload> elicit(
                    com.javaclaw.api.McpElicitationRequest request, com.javaclaw.api.CancellationToken cancellation) {
                return Optional.empty();
            }

            @Override
            public Optional<CanonicalPayload> sample(
                    com.javaclaw.api.McpSamplingRequest request, com.javaclaw.api.CancellationToken cancellation) {
                return Optional.empty();
            }
        };
    }

    private static McpClientInteractionPort interactions(
            CanonicalJson json, AtomicInteger elicited, AtomicInteger sampled) {
        return new McpClientInteractionPort() {
            @Override
            public Optional<CanonicalPayload> elicit(
                    com.javaclaw.api.McpElicitationRequest request, com.javaclaw.api.CancellationToken cancellation) {
                elicited.incrementAndGet();
                assertEquals("请选择", request.prompt());
                return Optional.of(json.parse("{\"choice\":\"safe\"}"));
            }

            @Override
            public Optional<CanonicalPayload> sample(
                    com.javaclaw.api.McpSamplingRequest request, com.javaclaw.api.CancellationToken cancellation) {
                sampled.incrementAndGet();
                assertEquals(McpSamplingRole.USER, request.messages().getFirst().role());
                return Optional.of(
                        json.parse(
                                "{\"content\":{\"text\":\"sampled\",\"type\":\"text\"},\"role\":\"assistant\",\"stopReason\":\"endTurn\"}"));
            }
        };
    }

    private static void serveInteractionFlow(Peer peer, CanonicalJson json) throws Exception {
        CanonicalPayload initialize = peer.read(json);
        assertEquals("initialize", json.textField(initialize, "method").orElseThrow());
        assertTrue(json.objectField(initialize, "params")
                .flatMap(params -> json.objectField(params, "capabilities"))
                .map(json::fieldNames)
                .orElseThrow()
                .containsAll(java.util.Set.of("elicitation", "sampling")));
        peer.write(
                json,
                response(
                        "javaclaw-stdio-1",
                        Map.of("protocolVersion", McpProtocol.VERSION, "capabilities", Map.of("tools", Map.of()))));
        assertEquals(
                "notifications/initialized",
                json.textField(peer.read(json), "method").orElseThrow());

        CanonicalPayload toolCall = peer.read(json);
        assertEquals("tools/call", json.textField(toolCall, "method").orElseThrow());
        peer.write(
                json,
                request(
                        "remote-elicit",
                        "elicitation/create",
                        Map.of(
                                "message",
                                "请选择",
                                "requestedSchema",
                                Map.of(
                                        "type",
                                        "object",
                                        "additionalProperties",
                                        false,
                                        "properties",
                                        Map.of("choice", Map.of("type", "string")),
                                        "required",
                                        java.util.List.of("choice")))));
        CanonicalPayload elicitation = peer.read(json);
        CanonicalPayload elicitationResult =
                json.objectField(elicitation, "result").orElseThrow();
        assertEquals("accept", json.textField(elicitationResult, "action").orElseThrow());

        peer.write(
                json,
                request(
                        "remote-sample",
                        "sampling/createMessage",
                        Map.of(
                                "maxTokens",
                                64,
                                "messages",
                                java.util.List.of(
                                        Map.of("role", "user", "content", Map.of("type", "text", "text", "外部数据"))))));
        CanonicalPayload sample = json.objectField(peer.read(json), "result").orElseThrow();
        assertEquals("assistant", json.textField(sample, "role").orElseThrow());
        peer.write(json, response("javaclaw-stdio-2", Map.of("status", "done")));
    }

    private static Map<String, Object> request(String id, String method, Map<String, Object> params) {
        return Map.of("jsonrpc", "2.0", "id", id, "method", method, "params", params);
    }

    private static Map<String, Object> response(String id, Map<String, Object> result) {
        return Map.of("jsonrpc", "2.0", "id", id, "result", result);
    }

    private static McpEndpoint endpoint() {
        return endpoint(Duration.ofSeconds(2));
    }

    private static McpEndpoint endpoint(Duration timeout) {
        McpEndpointSpec spec = new McpEndpointSpec(
                WorkspaceId.random(),
                "Signed MCP",
                McpTransport.SIGNED_BUNDLE_STDIO,
                Optional.empty(),
                Optional.of("signed.bundle"),
                McpAuthType.NONE,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                timeout);
        return new McpEndpoint("signed", 1, McpEndpointState.ENABLED, 1, spec, NOW, NOW);
    }

    @FunctionalInterface
    private interface Script {
        void run(Peer peer) throws Exception;
    }

    private record Peer(BufferedReader input, BufferedWriter output) {
        private CanonicalPayload read(CanonicalJson json) throws IOException {
            String line = input.readLine();
            if (line == null) {
                throw new IOException("client closed scripted MCP pipe");
            }
            return json.parse(line);
        }

        private void write(CanonicalJson json, Object value) throws IOException {
            output.write(json.encode(value).json());
            output.newLine();
            output.flush();
        }
    }

    private static final class ScriptedProcess extends Process {
        private final PipedOutputStream clientInput = new PipedOutputStream();
        private final PipedInputStream clientOutput;
        private final PipedInputStream serverInput;
        private final PipedOutputStream serverOutput = new PipedOutputStream();
        private final AtomicReference<Throwable> failure = new AtomicReference<>();
        private final Thread server;

        private ScriptedProcess(Script script) throws IOException {
            serverInput = new PipedInputStream(clientInput);
            clientOutput = new PipedInputStream(serverOutput);
            server = Thread.startVirtualThread(() -> {
                try (BufferedReader input =
                                new BufferedReader(new InputStreamReader(serverInput, StandardCharsets.UTF_8));
                        BufferedWriter output =
                                new BufferedWriter(new OutputStreamWriter(serverOutput, StandardCharsets.UTF_8))) {
                    script.run(new Peer(input, output));
                } catch (Throwable problem) {
                    failure.set(problem);
                }
            });
        }

        private void assertSucceeded() throws InterruptedException {
            server.join(Duration.ofSeconds(2));
            if (failure.get() != null) {
                throw new AssertionError("scripted MCP peer failed", failure.get());
            }
            assertTrue(!server.isAlive(), "scripted MCP peer did not finish");
        }

        @Override
        public OutputStream getOutputStream() {
            return clientInput;
        }

        @Override
        public InputStream getInputStream() {
            return clientOutput;
        }

        @Override
        public InputStream getErrorStream() {
            return new ByteArrayInputStream(new byte[0]);
        }

        @Override
        public int waitFor() throws InterruptedException {
            server.join();
            return 0;
        }

        @Override
        public boolean waitFor(long timeout, TimeUnit unit) throws InterruptedException {
            server.join(Duration.ofNanos(unit.toNanos(timeout)));
            return !server.isAlive();
        }

        @Override
        public int exitValue() {
            if (server.isAlive()) {
                throw new IllegalThreadStateException("scripted MCP peer is alive");
            }
            return 0;
        }

        @Override
        public void destroy() {
            try {
                clientInput.close();
                clientOutput.close();
            } catch (IOException ignored) {
                // 测试进程可能已经随完整帧结束。
            }
        }

        @Override
        public Process destroyForcibly() {
            destroy();
            server.interrupt();
            return this;
        }

        @Override
        public boolean isAlive() {
            return server.isAlive();
        }
    }

    private static final class StaticInputProcess extends Process {
        private final InputStream input;
        private final OutputStream output = new ByteArrayOutputStream();

        private StaticInputProcess(String input) {
            this.input = new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8));
        }

        @Override
        public OutputStream getOutputStream() {
            return output;
        }

        @Override
        public InputStream getInputStream() {
            return input;
        }

        @Override
        public InputStream getErrorStream() {
            return new ByteArrayInputStream(new byte[0]);
        }

        @Override
        public int waitFor() {
            return 0;
        }

        @Override
        public boolean waitFor(long timeout, TimeUnit unit) {
            return true;
        }

        @Override
        public int exitValue() {
            return 0;
        }

        @Override
        public void destroy() {}

        @Override
        public Process destroyForcibly() {
            return this;
        }

        @Override
        public boolean isAlive() {
            return false;
        }
    }
}
