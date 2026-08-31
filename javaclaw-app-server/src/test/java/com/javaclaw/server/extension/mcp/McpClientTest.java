package com.javaclaw.server.extension.mcp;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.javaclaw.agent.runtime.ToolExecutionContext;
import com.javaclaw.core.api.AgentThread;
import com.javaclaw.core.api.AgentTurn;
import com.javaclaw.core.api.ApprovalPolicy;
import com.javaclaw.core.api.AttemptId;
import com.javaclaw.core.api.ModelToolCall;
import com.javaclaw.core.api.ThreadId;
import com.javaclaw.core.api.ThreadStatus;
import com.javaclaw.core.api.TurnConfig;
import com.javaclaw.core.api.TurnId;
import com.javaclaw.core.api.TurnInput;
import com.javaclaw.core.api.TurnStatus;
import com.javaclaw.sandbox.api.NetworkPolicy;
import com.javaclaw.sandbox.api.SandboxMode;
import com.javaclaw.sandbox.api.SandboxPolicy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class McpClientTest {
    private final ObjectMapper json = new ObjectMapper();
    private final Instant taskCreatedAt = Instant.now();

    @TempDir
    Path temporary;

    @Test
    void paginatesAndCachesToolDiscovery() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        McpTransport transport = new McpTransport() {
            @Override
            public JsonNode exchange(
                    ObjectNode request,
                    Map<String, String> headers,
                    Duration timeout,
                    Consumer<JsonNode> notifications) {
                calls.incrementAndGet();
                ObjectNode response = json.createObjectNode();
                response.put("jsonrpc", "2.0");
                response.set("id", request.get("id").deepCopy());
                ObjectNode result = response.putObject("result");
                result.put("resultType", "complete");
                result.put("ttlMs", 60_000);
                result.put("cacheScope", "private");
                if (request.path("method").asText().equals(McpProtocol.DISCOVER)) {
                    result.putArray("supportedVersions").add(McpProtocol.VERSION);
                    result.putObject("capabilities").putObject("tools");
                } else {
                    var tools = result.putArray("tools");
                    String cursor = request.path("params").path("cursor").asText("");
                    tools.addObject()
                            .put("name", cursor.isEmpty() ? "first" : "second")
                            .put("description", "test")
                            .putObject("inputSchema")
                            .put("type", "object");
                    if (cursor.isEmpty()) {
                        result.put("nextCursor", "page-2");
                    }
                }
                return response;
            }

            @Override
            public void cancel(JsonNode requestId, String reason) {}

            @Override
            public void close() {}
        };
        McpCodec codec = new McpCodec(json);
        try (McpClient client = new McpClient("server", 1, transport, codec, json, McpInputResolver.REJECT_ALL)) {
            assertEquals(2, client.listTools().size());
            assertEquals(2, client.listTools().size());
        }
        assertEquals(3, calls.get()); // discover + two pages; second list is cached
    }

    @Test
    void drivesTaskPollingAndSendsBareDeduplicatedInputResponses() throws Exception {
        AtomicInteger taskGets = new AtomicInteger();
        AtomicInteger updates = new AtomicInteger();
        McpTransport transport = new RespondingTransport() {
            @Override
            ObjectNode result(ObjectNode request, Map<String, String> headers) {
                String method = request.path("method").asText();
                if (method.equals(McpProtocol.TOOLS_CALL)) {
                    return task(json.createObjectNode()
                            .put("resultType", "task")
                            .put("taskId", "task-1")
                            .put("status", "working")
                            .put("ttlMs", 3_600_000)
                            .put("pollIntervalMs", 10));
                }
                assertEquals("task-1", headers.get("Mcp-Name"));
                if (method.equals(McpProtocol.TASKS_GET) && taskGets.getAndIncrement() == 0) {
                    ObjectNode result = json.createObjectNode()
                            .put("resultType", "complete")
                            .put("taskId", "task-1")
                            .put("status", "input_required")
                            .put("ttlMs", 3_600_000)
                            .put("pollIntervalMs", 10);
                    result.putObject("inputRequests")
                            .putObject("confirm")
                            .put("method", "elicitation/create")
                            .putObject("params")
                            .put("message", "Continue?");
                    return task(result);
                }
                if (method.equals(McpProtocol.TASKS_UPDATE)) {
                    updates.incrementAndGet();
                    JsonNode answer =
                            request.path("params").path("inputResponses").path("confirm");
                    assertEquals("accept", answer.path("action").asText());
                    assertFalse(answer.has("resultType"));
                    return json.createObjectNode().put("resultType", "complete");
                }
                if (method.equals(McpProtocol.TASKS_GET)) {
                    ObjectNode result = json.createObjectNode()
                            .put("resultType", "complete")
                            .put("taskId", "task-1")
                            .put("status", "completed")
                            .put("ttlMs", 3_600_000)
                            .put("pollIntervalMs", 10);
                    result.putObject("result")
                            .put("resultType", "complete")
                            .putArray("content")
                            .addObject()
                            .put("type", "text")
                            .put("text", "finished");
                    return task(result);
                }
                throw new AssertionError("unexpected method " + method);
            }
        };
        McpInputResolver resolver =
                (id, request, invocation) -> json.createObjectNode().put("action", "accept");
        McpRemoteTool tool =
                new McpRemoteTool("run", "", "", json.createObjectNode(), null, null, json.createObjectNode());
        try (McpClient client = new McpClient("server", 1, transport, new McpCodec(json), json, resolver)) {
            JsonNode result = client.callTool(tool, json.createObjectNode(), invocation());
            assertEquals("finished", result.path("content").get(0).path("text").asText());
        }
        assertEquals(2, taskGets.get());
        assertEquals(1, updates.get());
    }

    @Test
    void rejectsMissingModernCacheMetadataAndSkipsOnlyMalformedTools() throws Exception {
        AtomicInteger mode = new AtomicInteger();
        McpTransport transport = new RespondingTransport() {
            @Override
            ObjectNode result(ObjectNode request, Map<String, String> headers) {
                ObjectNode result = json.createObjectNode().put("resultType", "complete");
                if (request.path("method").asText().equals(McpProtocol.DISCOVER)) {
                    result.putArray("supportedVersions").add(McpProtocol.VERSION);
                    result.putObject("capabilities").putObject("tools");
                    if (mode.get() > 0) {
                        result.put("ttlMs", 1_000).put("cacheScope", "private");
                    }
                } else {
                    result.put("ttlMs", 1_000).put("cacheScope", "private");
                    var tools = result.putArray("tools");
                    tools.addObject()
                            .put("name", "bad")
                            .putObject("inputSchema")
                            .putObject("properties")
                            .putObject("secret")
                            .put("type", "number")
                            .put("x-mcp-header", "Secret");
                    tools.addObject()
                            .put("name", "good")
                            .putObject("inputSchema")
                            .put("type", "object");
                }
                return result;
            }
        };
        try (McpClient invalid =
                new McpClient("server", 1, transport, new McpCodec(json), json, McpInputResolver.REJECT_ALL)) {
            assertThrows(McpProtocolException.class, invalid::discover);
        }
        mode.incrementAndGet();
        try (McpClient valid =
                new McpClient("server", 1, transport, new McpCodec(json), json, McpInputResolver.REJECT_ALL)) {
            assertEquals(
                    List.of("good"),
                    valid.listTools().stream().map(McpRemoteTool::name).toList());
        }
    }

    @Test
    void validatesSubscriptionAcknowledgementIdAndAcceptedFilter() throws Exception {
        CountDownLatch delivered = new CountDownLatch(2);
        AtomicInteger listenCalls = new AtomicInteger();
        McpTransport transport = new RespondingTransport() {
            @Override
            public JsonNode exchange(
                    ObjectNode request,
                    Map<String, String> headers,
                    Duration timeout,
                    Consumer<JsonNode> notifications) {
                if (!McpProtocol.SUBSCRIPTIONS_LISTEN.equals(
                        request.path("method").asText())) {
                    return super.exchange(request, headers, timeout, notifications);
                }
                listenCalls.incrementAndGet();
                ObjectNode acknowledged = subscriptionNotification(request, "notifications/subscriptions/acknowledged");
                ((ObjectNode) acknowledged.path("params"))
                        .putObject("notifications")
                        .put("toolsListChanged", true);
                notifications.accept(acknowledged);
                ObjectNode changed = subscriptionNotification(request, "notifications/tools/list_changed");
                notifications.accept(changed);
                ObjectNode response = json.createObjectNode().put("jsonrpc", "2.0");
                response.set("id", request.get("id").deepCopy());
                ObjectNode result = response.putObject("result").put("resultType", "complete");
                result.putObject("_meta")
                        .set(
                                "io.modelcontextprotocol/subscriptionId",
                                request.get("id").deepCopy());
                return response;
            }

            @Override
            ObjectNode result(ObjectNode request, Map<String, String> headers) {
                ObjectNode result = json.createObjectNode()
                        .put("resultType", "complete")
                        .put("ttlMs", 60_000)
                        .put("cacheScope", "private");
                result.putArray("supportedVersions").add(McpProtocol.VERSION);
                result.putObject("capabilities").putObject("tools").put("listChanged", true);
                return result;
            }
        };
        try (McpClient client =
                new McpClient("server", 1, transport, new McpCodec(json), json, McpInputResolver.REJECT_ALL)) {
            client.listen(
                    new McpClient.SubscriptionFilter(true, false, false, List.of(), List.of()),
                    ignored -> delivered.countDown());
            assertTrue(delivered.await(1, TimeUnit.SECONDS));
            client.stopListening();
        }
        assertEquals(1, listenCalls.get());

        var requested = new McpClient.SubscriptionFilter(true, false, false, List.of(), List.of("task-1"));
        assertFalse(new McpClient.SubscriptionFilter(false, true, false, List.of(), List.of()).subsetOf(requested));
        assertThrows(
                IllegalStateException.class,
                () -> McpClient.SubscriptionFilter.fromJson(json.readTree("{\"unknown\":true}")));
    }

    private ObjectNode subscriptionNotification(ObjectNode request, String method) {
        ObjectNode notification = json.createObjectNode().put("jsonrpc", "2.0").put("method", method);
        notification
                .putObject("params")
                .putObject("_meta")
                .set("io.modelcontextprotocol/subscriptionId", request.get("id").deepCopy());
        return notification;
    }

    private McpInvocation invocation() {
        Instant now = Instant.now();
        ThreadId threadId = ThreadId.random();
        TurnId turnId = TurnId.random();
        SandboxPolicy policy = new SandboxPolicy(
                SandboxMode.READ_ONLY,
                Set.of(temporary),
                Set.of(),
                Set.of(),
                NetworkPolicy.disabled(),
                Set.of(),
                Duration.ofSeconds(2),
                SandboxPolicy.DEFAULT_OUTPUT_LIMIT);
        TurnConfig config = new TurnConfig(
                "model", "provider", "medium", temporary, policy, ApprovalPolicy.ON_RISK, Set.of(), Map.of());
        AgentThread thread = new AgentThread(
                threadId, "workspace", null, null, "", temporary, ThreadStatus.ACTIVE, 0, 0, 1, now, now);
        AgentTurn turn = new AgentTurn(
                turnId,
                threadId,
                AttemptId.random(),
                TurnStatus.IN_PROGRESS,
                List.of(new TurnInput.Text("test")),
                config,
                null,
                now,
                null);
        ToolExecutionContext context =
                new ToolExecutionContext(thread, turn, new ModelToolCall("call", "mcp", "{}"), config);
        return new McpInvocation(context, ignored -> null);
    }

    private ObjectNode task(ObjectNode result) {
        result.put("createdAt", taskCreatedAt.toString());
        result.put("lastUpdatedAt", taskCreatedAt.plusSeconds(1).toString());
        if (!result.has("ttlMs")) {
            result.put("ttlMs", 3_600_000);
        }
        return result;
    }

    private abstract class RespondingTransport implements McpTransport {
        @Override
        public JsonNode exchange(
                ObjectNode request, Map<String, String> headers, Duration timeout, Consumer<JsonNode> notifications) {
            ObjectNode response = json.createObjectNode();
            response.put("jsonrpc", "2.0");
            response.set("id", request.get("id").deepCopy());
            response.set("result", result(request, headers));
            return response;
        }

        abstract ObjectNode result(ObjectNode request, Map<String, String> headers);

        @Override
        public void cancel(JsonNode requestId, String reason) {}

        @Override
        public void close() {}
    }
}
