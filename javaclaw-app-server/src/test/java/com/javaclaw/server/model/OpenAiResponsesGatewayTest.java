package com.javaclaw.server.model;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.javaclaw.agent.model.CompactionStrategy;
import com.javaclaw.agent.model.ContextWindowExceededException;
import com.javaclaw.core.api.ApprovalPolicy;
import com.javaclaw.core.api.ModelMessage;
import com.javaclaw.core.api.ModelRequest;
import com.javaclaw.core.api.ModelToolCall;
import com.javaclaw.core.api.ThreadId;
import com.javaclaw.core.api.ToolDescriptor;
import com.javaclaw.core.api.TurnConfig;
import com.javaclaw.core.api.TurnId;
import com.javaclaw.sandbox.api.SandboxPolicy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenAiResponsesGatewayTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path temporary;

    @Test
    void usesStatelessResponsesCompactAndReplaysOpaqueItemsInCanonicalOrder() throws Exception {
        try (var server = new FakeResponsesServer()) {
            server.enqueue(200, normalResponse(false));
            server.enqueue(200, compactedResponse());
            server.enqueue(200, normalResponse(true));
            try (var gateway = SpringAiCloudModelGateway.fromEnvironment(environment(server, true))) {
                TurnConfig config = config(Map.of(
                        "modelContextWindowTokens", "1000",
                        "maxOutputTokens", "256"));
                List<ModelMessage> firstMessages = List.of(
                        new ModelMessage(ModelMessage.Role.SYSTEM, "固定系统策略", null),
                        new ModelMessage(ModelMessage.Role.USER, "AGENTS.md：只能修改工作区", null),
                        new ModelMessage(ModelMessage.Role.USER, "开始处理", null));
                var tool = new ToolDescriptor("read_file", "读取文件", """
                        {"type":"object","properties":{"path":{"type":"string"}},
                         "required":["path"],"additionalProperties":false}
                        """);
                var first = gateway.complete(new ModelRequest(
                        new ThreadId("thread"), new TurnId("turn-1"), firstMessages, List.of(tool), config, null, 2));

                assertEquals("完成", first.text());
                assertEquals("可展示推理摘要", first.reasoningSummary());
                assertEquals("read_file", first.toolCalls().getFirst().name());
                assertEquals(10, first.usage().inputTokens());
                assertFalse(first.conversationState().compacted());
                assertTrue(first.conversationState().payloadJson().contains("encrypted_content"));
                assertTrue(first.conversationState().payloadJson().contains("final_answer"));
                assertTrue(first.conversationState().payloadJson().contains("function_call"));

                CapturedRequest create = server.awaitRequest();
                assertTrue(create.path().endsWith("/responses"));
                JsonNode createBody = JSON.readTree(create.body());
                assertFalse(createBody.path("store").asBoolean(true));
                assertEquals("固定系统策略", createBody.path("instructions").asText());
                assertEquals(
                        "user", createBody.path("input").get(0).path("role").asText());
                assertTrue(createBody.path("input").get(0).toString().contains("AGENTS.md"));
                assertTrue(createBody.path("include").toString().contains("reasoning.encrypted_content"));
                assertEquals(
                        800,
                        createBody
                                .path("context_management")
                                .get(0)
                                .path("compact_threshold")
                                .asInt());

                List<ModelMessage> compactMessages = new ArrayList<>(firstMessages);
                compactMessages.add(new ModelMessage(
                        ModelMessage.Role.ASSISTANT,
                        "完成",
                        null,
                        null,
                        List.of(new ModelToolCall("call-1", "read_file", "{\"path\":\"README.md\"}"))));
                compactMessages.add(new ModelMessage(ModelMessage.Role.TOOL, "文件内容", "call-1", "read_file", List.of()));
                var compacted = gateway.compact(new ModelRequest(
                        new ThreadId("thread"),
                        new TurnId("turn-compact"),
                        compactMessages,
                        List.of(),
                        config,
                        first.conversationState(),
                        2));
                assertFalse(compacted.state().compacted(), "compact endpoint 返回可直接跨 Turn 持久化的状态");
                assertTrue(compacted.state().payloadJson().contains("opaque-manual"));

                CapturedRequest compact = server.awaitRequest();
                assertTrue(compact.path().endsWith("/responses/compact"));
                JsonNode compactBody = JSON.readTree(compact.body());
                assertEquals("固定系统策略", compactBody.path("instructions").asText());
                String compactInput = compactBody.path("input").toString();
                assertTrue(compactInput.indexOf("AGENTS.md") < compactInput.indexOf("encrypted_content"));
                assertTrue(compactInput.indexOf("encrypted_content") < compactInput.indexOf("文件内容"));
                assertTrue(compactInput.contains("final_answer"));

                var after = gateway.complete(new ModelRequest(
                        new ThreadId("thread"),
                        new TurnId("turn-2"),
                        List.of(
                                new ModelMessage(ModelMessage.Role.SYSTEM, "固定系统策略", null),
                                new ModelMessage(ModelMessage.Role.USER, "AGENTS.md：只能修改工作区", null),
                                new ModelMessage(ModelMessage.Role.USER, "压缩后继续", null)),
                        List.of(),
                        config,
                        compacted.state().persistent(),
                        2));
                assertTrue(after.conversationState().compacted(), "普通 Responses 返回 compaction item 时应安装新窗口");
                assertTrue(after.conversationState().payloadJson().contains("opaque-auto"));
                assertFalse(after.conversationState().payloadJson().contains("压缩后继续"));

                CapturedRequest replay = server.awaitRequest();
                JsonNode replayBody = JSON.readTree(replay.body());
                assertFalse(replayBody.path("store").asBoolean(true));
                String replayInput = replayBody.path("input").toString();
                assertTrue(replayInput.indexOf("AGENTS.md") < replayInput.indexOf("opaque-manual"));
                assertTrue(replayInput.indexOf("opaque-manual") < replayInput.indexOf("压缩后继续"));
            }
        }
    }

    @Test
    void customEndpointsRequireExplicitNativeCapabilityAndContextErrorsRemainTyped() throws Exception {
        try (var server = new FakeResponsesServer()) {
            try (var summary = SpringAiCloudModelGateway.fromEnvironment(environment(server, false))) {
                assertEquals(CompactionStrategy.SUMMARY, summary.compactionStrategy(config(Map.of())));
            }
            try (var nativeGateway = SpringAiCloudModelGateway.fromEnvironment(environment(server, true))) {
                assertEquals(CompactionStrategy.NATIVE, nativeGateway.compactionStrategy(config(Map.of())));
                server.enqueue(400, """
                        {"error":{"message":"maximum context window exceeded",
                         "type":"invalid_request_error","param":"input","code":"context_length_exceeded"}}
                        """);
                Exception failure = assertThrows(
                        Exception.class,
                        () -> nativeGateway.complete(new ModelRequest(
                                new ThreadId("thread-error"),
                                new TurnId("turn-error"),
                                List.of(
                                        new ModelMessage(ModelMessage.Role.SYSTEM, "policy", null),
                                        new ModelMessage(ModelMessage.Role.USER, "too long", null)),
                                List.of(),
                                config(Map.of()),
                                null,
                                1)));
                assertInstanceOf(ContextWindowExceededException.class, failure);
            }
            try (var official = SpringAiCloudModelGateway.fromEnvironment(Map.of(
                    "OPENAI_API_KEY", "test-key",
                    "JAVACLAW_OPENAI_MODEL", "gpt-5"))) {
                assertEquals(CompactionStrategy.NATIVE, official.compactionStrategy(config(Map.of())));
            }
            try (var explicitOfficial = SpringAiCloudModelGateway.fromEnvironment(Map.of(
                    "OPENAI_API_KEY",
                    "test-key",
                    "OPENAI_BASE_URL",
                    "https://api.openai.com/v1",
                    "JAVACLAW_OPENAI_MODEL",
                    "gpt-5"))) {
                assertEquals(CompactionStrategy.NATIVE, explicitOfficial.compactionStrategy(config(Map.of())));
            }
        }
    }

    @Test
    void bareCompatibleEndpointNeedsNoCredentialAndStreamsRealResponseEvents() throws Exception {
        try (var server = new FakeResponsesServer()) {
            server.enqueueStream(200, streamedResponse());
            try (var gateway = SpringAiCloudModelGateway.fromEnvironment(
                    Map.of("OPENAI_BASE_URL", server.baseUrl(), "JAVACLAW_OPENAI_MODEL", "gpt-5"))) {
                assertTrue(gateway.descriptors().stream()
                        .filter(value -> "openai".equals(value.provider()))
                        .findFirst()
                        .orElseThrow()
                        .configured());
                ArrayList<String> text = new ArrayList<>();
                ArrayList<String> reasoning = new ArrayList<>();
                var result = gateway.stream(
                        new ModelRequest(
                                new ThreadId("stream-thread"),
                                new TurnId("stream-turn"),
                                List.of(new ModelMessage(ModelMessage.Role.USER, "stream", null)),
                                List.of(),
                                config(Map.of("maxOutputTokens", "256"))),
                        new com.javaclaw.core.api.ModelStreamSink() {
                            @Override
                            public void text(String fragment) {
                                text.add(fragment);
                            }

                            @Override
                            public void reasoningSummary(String fragment) {
                                reasoning.add(fragment);
                            }
                        });
                assertEquals(List.of("完", "成"), text);
                assertEquals(List.of("可展示推理摘要"), reasoning);
                assertEquals("完成", result.text());
                assertEquals(10, result.usage().inputTokens());

                CapturedRequest request = server.awaitRequest();
                assertEquals("/v1/responses", request.path());
                assertTrue(JSON.readTree(request.body()).path("stream").asBoolean());
            }
        }
    }

    private Map<String, String> environment(FakeResponsesServer server, boolean nativeCompaction) {
        return Map.of(
                "OPENAI_API_KEY",
                "test-key",
                "OPENAI_BASE_URL",
                server.baseUrl() + "/v1",
                "JAVACLAW_OPENAI_MODEL",
                "gpt-5",
                "JAVACLAW_OPENAI_NATIVE_COMPACTION",
                Boolean.toString(nativeCompaction));
    }

    private TurnConfig config(Map<String, String> attributes) {
        return new TurnConfig(
                "gpt-5",
                "openai",
                "medium",
                temporary,
                SandboxPolicy.readOnly(Set.of(temporary), Set.of()),
                ApprovalPolicy.NEVER,
                Set.of(),
                attributes);
    }

    private static String normalResponse(boolean withCompaction) {
        String prefix = withCompaction
                ? "{\"id\":\"cmp-auto\",\"type\":\"compaction\",\"encrypted_content\":\"opaque-auto\"},"
                : "";
        return """
                {"id":"resp-1","created_at":1,"object":"response","status":"completed","model":"gpt-5",
                 "output":[%s
                  {"id":"reason-1","type":"reasoning","summary":[{"type":"summary_text","text":"可展示推理摘要"}],
                   "encrypted_content":"encrypted-reasoning","status":"completed"},
                  {"id":"message-1","type":"message","role":"assistant","status":"completed","phase":"final_answer",
                   "content":[{"type":"output_text","text":"完成","annotations":[],"logprobs":[]}]},
                  {"id":"function-1","type":"function_call","call_id":"call-1","name":"read_file",
                   "arguments":"{}","status":"completed"}],
                 "usage":{"input_tokens":10,"input_tokens_details":{"cached_tokens":0},
                  "output_tokens":5,"output_tokens_details":{"reasoning_tokens":2},"total_tokens":15}}
                """.formatted(prefix);
    }

    private static String compactedResponse() {
        return """
                {"id":"compact-1","created_at":1,"object":"response.compaction",
                 "output":[{"id":"cmp-manual","type":"compaction","encrypted_content":"opaque-manual"}],
                 "usage":{"input_tokens":12,"input_tokens_details":{"cached_tokens":0},
                  "output_tokens":3,"output_tokens_details":{"reasoning_tokens":0},"total_tokens":15}}
                """;
    }

    private static String streamedResponse() {
        return """
                event: response.reasoning_summary_text.delta
                data: {"type":"response.reasoning_summary_text.delta","item_id":"reason-1",\
                "output_index":0,"summary_index":0,"delta":"可展示推理摘要","sequence_number":1}

                event: response.output_text.delta
                data: {"type":"response.output_text.delta","item_id":"message-1","output_index":1,\
                "content_index":0,"delta":"完","logprobs":[],"sequence_number":2}

                event: response.output_text.delta
                data: {"type":"response.output_text.delta","item_id":"message-1","output_index":1,\
                "content_index":0,"delta":"成","logprobs":[],"sequence_number":3}

                event: response.completed
                data: {"type":"response.completed","response":%s,"sequence_number":4}

                """.formatted(normalResponse(false).replace("\n", " "));
    }

    private record CapturedRequest(String path, String body) {}

    /** 只在 loopback 提供有界 HTTP/1.1 fake，不访问外网或付费模型。 */
    private static final class FakeResponsesServer implements AutoCloseable {
        private final ServerSocket socket;
        private final LinkedBlockingQueue<ScriptedResponse> responses = new LinkedBlockingQueue<>();
        private final LinkedBlockingQueue<CapturedRequest> requests = new LinkedBlockingQueue<>();
        private final CopyOnWriteArrayList<Throwable> failures = new CopyOnWriteArrayList<>();
        private final Thread worker;

        private FakeResponsesServer() throws IOException {
            socket = new ServerSocket(0, 16, InetAddress.getLoopbackAddress());
            worker = Thread.ofVirtual().name("openai-responses-fake").start(this::serve);
        }

        private String baseUrl() {
            return "http://" + socket.getInetAddress().getHostAddress() + ":" + socket.getLocalPort();
        }

        private void enqueue(int status, String body) {
            responses.add(new ScriptedResponse(status, "application/json", body));
        }

        private void enqueueStream(int status, String body) {
            responses.add(new ScriptedResponse(status, "text/event-stream", body));
        }

        private CapturedRequest awaitRequest() throws Exception {
            CapturedRequest request = requests.poll(3, TimeUnit.SECONDS);
            if (request == null) {
                Throwable failure = failures.isEmpty() ? null : failures.getFirst();
                throw new AssertionError("fake OpenAI 请求超时", failure);
            }
            return request;
        }

        private void serve() {
            while (!socket.isClosed()) {
                try (Socket accepted = socket.accept()) {
                    handle(accepted);
                } catch (SocketException closed) {
                    if (!socket.isClosed()) {
                        failures.add(closed);
                    }
                } catch (Throwable failure) {
                    failures.add(failure);
                }
            }
        }

        private void handle(Socket accepted) throws Exception {
            accepted.setSoTimeout(Math.toIntExact(Duration.ofSeconds(5).toMillis()));
            InputStream input = accepted.getInputStream();
            String requestLine = readLine(input);
            if (requestLine == null || requestLine.isBlank()) {
                throw new IOException("missing request line");
            }
            int contentLength = 0;
            String line;
            while ((line = readLine(input)) != null && !line.isEmpty()) {
                int separator = line.indexOf(':');
                if (separator > 0
                        && "content-length"
                                .equals(line.substring(0, separator).strip().toLowerCase(Locale.ROOT))) {
                    contentLength =
                            Integer.parseInt(line.substring(separator + 1).strip());
                }
            }
            byte[] body = input.readNBytes(contentLength);
            requests.add(new CapturedRequest(requestLine.split(" ", 3)[1], new String(body, StandardCharsets.UTF_8)));
            ScriptedResponse response = responses.poll(5, TimeUnit.SECONDS);
            if (response == null) {
                response = new ScriptedResponse(
                        500, "application/json", "{\"error\":{\"message\":\"unscripted request\"}}");
            }
            byte[] responseBody = response.body().getBytes(StandardCharsets.UTF_8);
            String reason = response.status() >= 400 ? "Bad Request" : "OK";
            accepted.getOutputStream()
                    .write(("HTTP/1.1 " + response.status() + " " + reason + "\r\n"
                                    + "Content-Type: " + response.contentType() + "\r\n"
                                    + "Content-Length: " + responseBody.length + "\r\n"
                                    + "Connection: close\r\n\r\n")
                            .getBytes(StandardCharsets.US_ASCII));
            accepted.getOutputStream().write(responseBody);
            accepted.getOutputStream().flush();
        }

        private static String readLine(InputStream input) throws IOException {
            ByteArrayOutputStream value = new ByteArrayOutputStream();
            int previous = -1;
            while (true) {
                int current = input.read();
                if (current < 0) {
                    return value.size() == 0 ? null : value.toString(StandardCharsets.US_ASCII);
                }
                if (previous == '\r' && current == '\n') {
                    byte[] bytes = value.toByteArray();
                    return new String(bytes, 0, Math.max(0, bytes.length - 1), StandardCharsets.US_ASCII);
                }
                value.write(current);
                previous = current;
            }
        }

        @Override
        public void close() throws Exception {
            socket.close();
            worker.join(Duration.ofSeconds(2));
            if (!failures.isEmpty()) {
                throw new AssertionError("fake OpenAI server failed", failures.getFirst());
            }
        }
    }

    private record ScriptedResponse(int status, String contentType, String body) {}
}
