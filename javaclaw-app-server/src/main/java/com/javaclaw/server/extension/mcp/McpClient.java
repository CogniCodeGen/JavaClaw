package com.javaclaw.server.extension.mcp;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Complete stateless MCP client facade: discovery, pagination/cache, MRTR, subscriptions and extension requests all
 * share one codec/transport path.
 */
public final class McpClient implements AutoCloseable {
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration MAX_CACHE_TTL = Duration.ofHours(24);

    private final String serverId;
    private final long revision;
    private final McpTransport transport;
    private final McpCodec codec;
    private final ObjectMapper json;
    private final McpInputResolver inputResolver;
    private final Clock clock;
    private final ConcurrentHashMap<String, CachedValue> cache = new ConcurrentHashMap<>();
    private final AtomicBoolean closed = new AtomicBoolean();
    private volatile Subscription subscription;

    /** 固定 Server 标识、配置修订与独立 transport；null MRTR resolver 按拒绝处理，close 释放订阅和传输。 */
    public McpClient(
            String serverId,
            long revision,
            McpTransport transport,
            McpCodec codec,
            ObjectMapper json,
            McpInputResolver inputResolver) {
        this(serverId, revision, transport, codec, json, inputResolver, Clock.systemUTC());
    }

    McpClient(
            String serverId,
            long revision,
            McpTransport transport,
            McpCodec codec,
            ObjectMapper json,
            McpInputResolver inputResolver,
            Clock clock) {
        this.serverId = require(serverId, "serverId");
        if (revision < 1) {
            throw new IllegalArgumentException("revision must be positive");
        }
        this.revision = revision;
        this.transport = Objects.requireNonNull(transport, "transport");
        this.codec = Objects.requireNonNull(codec, "codec");
        this.json = Objects.requireNonNull(json, "json");
        this.inputResolver = inputResolver == null ? McpInputResolver.REJECT_ALL : inputResolver;
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** 返回此客户端绑定的 MCP Server 标识。 */
    public String serverId() {
        return serverId;
    }

    /** 返回创建客户端时固定的配置版本；执行前仍要与持久记录复核。 */
    public long revision() {
        return revision;
    }

    /**
     * 发现并缓存服务能力，要求服务端明确支持固定版本及缓存元数据；不执行旧版 initialize 降级。
     *
     * @throws Exception 协议版本、缓存元数据或响应不合法
     */
    public McpDiscovery discover() throws Exception {
        JsonNode value = cached("discover", () -> invoke(McpProtocol.DISCOVER, json.createObjectNode(), null, null));
        requireComplete(value, "server/discover");
        requireCacheMetadata(value, "server/discover");
        List<String> versions = new ArrayList<>();
        value.path("supportedVersions").forEach(node -> versions.add(node.asText()));
        if (!versions.contains(McpProtocol.VERSION)) {
            throw new McpProtocolException(-32022, "MCP server does not support " + McpProtocol.VERSION, value);
        }
        JsonNode info = value.path("_meta").path("io.modelcontextprotocol/serverInfo");
        return new McpDiscovery(
                versions,
                value.path("capabilities"),
                value.path("instructions").asText(""),
                info.path("name").asText(""),
                info.path("version").asText(""),
                value);
    }

    /** 按分页读取并缓存工具目录；单工具 Schema/路由注解非法时仅排除该工具，不抹去健康工具。 */
    public List<McpRemoteTool> listTools() throws Exception {
        discover();
        JsonNode result = cached("tools", () -> listAll(McpProtocol.TOOLS_LIST, "tools"));
        ArrayList<McpRemoteTool> tools = new ArrayList<>();
        for (JsonNode value : result.path("items")) {
            JsonNode schema = value.get("inputSchema");
            if (schema == null || !schema.isObject()) {
                continue;
            }
            try {
                // Invalid routing annotations exclude only this tool, never the provider.
                McpHttpRoutingHeaders.forRequest(
                        codec.request(
                                McpProtocol.TOOLS_CALL,
                                json.createObjectNode()
                                        .put("name", value.path("name").asText())),
                        schema,
                        json.createObjectNode());
            } catch (IllegalArgumentException invalidRoutingMetadata) {
                continue;
            }
            tools.add(new McpRemoteTool(
                    value.path("name").asText(),
                    value.path("title").asText(""),
                    value.path("description").asText(""),
                    schema,
                    value.get("outputSchema"),
                    value.get("annotations"),
                    value));
        }
        return List.copyOf(tools);
    }

    /** 调用已发现工具并处理有界 MRTR 往返；调用方必须已完成统一治理，参数不会自动作为新权限。 */
    public JsonNode callTool(McpRemoteTool tool, JsonNode arguments, McpInvocation invocation) throws Exception {
        Objects.requireNonNull(tool, "tool");
        ObjectNode params = json.createObjectNode();
        params.put("name", tool.name());
        params.set("arguments", arguments == null ? json.createObjectNode() : arguments.deepCopy());
        return invoke(McpProtocol.TOOLS_CALL, params, tool.inputSchema(), invocation);
    }

    /** 分页读取并缓存 Resource 目录；结果只通过显式工具展示，不自动加入模型上下文。 */
    public JsonNode listResources() throws Exception {
        discover();
        return cached("resources", () -> listAll(McpProtocol.RESOURCES_LIST, "resources"));
    }

    /** 按参数读取资源，遵守资源缓存元数据并处理 MRTR；内容不自动注入 Context。 */
    public JsonNode readResource(JsonNode parameters, McpInvocation invocation) throws Exception {
        ObjectNode params = object(parameters);
        return cached("resource:" + cacheKey(params), () -> {
            JsonNode result = invoke(McpProtocol.RESOURCES_READ, params, null, invocation);
            requireCacheMetadata(result, "resources/read");
            return result;
        });
    }

    /** 分页读取并缓存 Resource Template 目录，保留未知扩展字段。 */
    public JsonNode listResourceTemplates() throws Exception {
        discover();
        return cached("resourceTemplates", () -> listAll(McpProtocol.RESOURCE_TEMPLATES_LIST, "resourceTemplates"));
    }

    /** 分页读取并缓存 Prompt 目录；模板不自动成为 Agent 系统提示词。 */
    public JsonNode listPrompts() throws Exception {
        discover();
        return cached("prompts", () -> listAll(McpProtocol.PROMPTS_LIST, "prompts"));
    }

    /** 显式请求 Prompt 展开结果并处理 MRTR，不改变本 Turn 固定 Profile。 */
    public JsonNode getPrompt(JsonNode parameters, McpInvocation invocation) throws Exception {
        return invoke(McpProtocol.PROMPT_GET, object(parameters), null, invocation);
    }

    /** 显式请求参数补全；返回 MCP 结果，不代替用户审批或自动执行工具。 */
    public JsonNode complete(JsonNode parameters, McpInvocation invocation) throws Exception {
        return invoke(McpProtocol.COMPLETION_COMPLETE, object(parameters), null, invocation);
    }

    /** Calls a negotiated extension method, including io.modelcontextprotocol/tasks methods. */
    public JsonNode extension(String method, JsonNode parameters, McpInvocation invocation) throws Exception {
        if (!McpProtocol.TASK_METHODS.contains(method)) {
            throw new IllegalArgumentException("unsupported MCP extension namespace");
        }
        return invoke(method, object(parameters), null, invocation);
    }

    /** 为当前客户端建立一条订阅后台循环；已有订阅时不重复启动，null filter 使用目录变更过滤器。 */
    public synchronized void listen(SubscriptionFilter filter, Consumer<JsonNode> notifications) throws Exception {
        if (subscription != null) {
            return;
        }
        discover();
        SubscriptionFilter requestedFilter = filter == null ? SubscriptionFilter.listChanges() : filter;
        ObjectNode params = json.createObjectNode();
        ObjectNode requested = params.putObject("notifications");
        if (requestedFilter.toolsListChanged()) {
            requested.put("toolsListChanged", true);
        }
        if (requestedFilter.promptsListChanged()) {
            requested.put("promptsListChanged", true);
        }
        if (requestedFilter.resourcesListChanged()) {
            requested.put("resourcesListChanged", true);
        }
        putStrings(requested, "resourceSubscriptions", requestedFilter.resourceSubscriptions());
        putStrings(requested, "taskIds", requestedFilter.taskIds());
        ObjectNode request = codec.request(McpProtocol.SUBSCRIPTIONS_LISTEN, params);
        AtomicBoolean active = new AtomicBoolean(true);
        Thread thread = Thread.ofVirtual()
                .name("javaclaw-mcp-subscription-" + serverId)
                .unstarted(() -> subscriptionLoop(request, requestedFilter, active, notifications));
        subscription = new Subscription(request.get("id").deepCopy(), active, thread);
        thread.start();
    }

    private void subscriptionLoop(
            ObjectNode request, SubscriptionFilter filter, AtomicBoolean active, Consumer<JsonNode> notifications) {
        long[] backoffMillis = {1_000, 2_000, 5_000, 10_000, 30_000, 60_000};
        int failures = 0;
        while (active.get() && !closed.get()) {
            try {
                AtomicBoolean acknowledged = new AtomicBoolean();
                AtomicReference<SubscriptionFilter> accepted = new AtomicReference<>();
                Map<String, String> headers = McpHttpRoutingHeaders.forRequest(request, null, null);
                JsonNode response = transport.exchange(request, headers, Duration.ofMinutes(5), value -> {
                    validateSubscriptionNotification(request.get("id"), filter, acknowledged, accepted, active, value);
                    invalidateFor(value.path("method").asText(""));
                    notifications.accept(value);
                });
                JsonNode result = codec.result(request, response);
                requireComplete(result, McpProtocol.SUBSCRIPTIONS_LISTEN);
                requireSubscriptionId(result.path("_meta"), request.get("id"));
                if (!acknowledged.get()) {
                    throw new McpProtocolException(-32603, "MCP subscription ended before acknowledgement", result);
                }
                if (!active.get() || closed.get()) {
                    break;
                }
                cache.clear();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception failure) {
                if (!active.get() || closed.get()) {
                    break;
                }
                cache.clear();
            }
            try {
                Thread.sleep(backoffMillis[Math.min(failures++, backoffMillis.length - 1)]);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        active.set(false);
    }

    private void validateSubscriptionNotification(
            JsonNode requestId,
            SubscriptionFilter requested,
            AtomicBoolean acknowledged,
            AtomicReference<SubscriptionFilter> accepted,
            AtomicBoolean active,
            JsonNode notification) {
        String method = notification.path("method").asText("");
        if (McpProtocol.CANCELLED.equals(method)) {
            if (!notification.path("params").path("requestId").equals(requestId)) {
                throw new IllegalStateException("MCP subscription cancellation id mismatch");
            }
            active.set(false);
            return;
        }
        requireSubscriptionId(notification.path("params").path("_meta"), requestId);
        if (method.equals("notifications/subscriptions/acknowledged")) {
            if (!acknowledged.compareAndSet(false, true)) {
                throw new IllegalStateException("MCP subscription acknowledged more than once");
            }
            SubscriptionFilter agreed =
                    SubscriptionFilter.fromJson(notification.path("params").path("notifications"));
            if (!agreed.subsetOf(requested)) {
                throw new IllegalStateException("MCP subscription acknowledged unrequested notifications");
            }
            accepted.set(agreed);
            return;
        }
        if (!acknowledged.get()) {
            throw new IllegalStateException("MCP subscription sent data before acknowledgement");
        }
        SubscriptionFilter effective = accepted.get();
        if (effective == null || !effective.accepts(notification)) {
            throw new IllegalStateException("MCP listen stream sent an unrequested notification");
        }
    }

    private static void requireSubscriptionId(JsonNode meta, JsonNode requestId) {
        JsonNode actual = meta.path("io.modelcontextprotocol/subscriptionId");
        if (!actual.equals(requestId)) {
            throw new IllegalStateException("MCP subscription id mismatch");
        }
    }

    /** 取消订阅请求并停止后台循环；不关闭其他正常调用，也不删除持久配置。 */
    public synchronized void stopListening() {
        Subscription current = subscription;
        subscription = null;
        if (current == null) {
            return;
        }
        current.active().set(false);
        transport.cancel(current.requestId(), "subscription stopped");
        current.thread().interrupt();
    }

    /** 清空本客户端的能力/目录/资源缓存；后续读取重新发现，不修改已生成的 Turn 工具描述符。 */
    public void invalidate() {
        cache.clear();
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        stopListening();
        cache.clear();
        transport.close();
    }

    private JsonNode invoke(String method, ObjectNode initialParams, JsonNode toolSchema, McpInvocation invocation)
            throws Exception {
        requireOpen();
        ObjectNode params = initialParams.deepCopy();
        for (int round = 0; round <= McpProtocol.MAX_MRTR_ROUNDS; round++) {
            ObjectNode request = codec.request(method, params);
            Map<String, String> headers =
                    McpHttpRoutingHeaders.forRequest(request, toolSchema, params.path("arguments"));
            JsonNode response = transport.exchange(request, headers, DEFAULT_TIMEOUT, this::acceptNotification);
            JsonNode result = codec.result(request, response);
            String resultType = result.path("resultType").asText("");
            if ("complete".equals(resultType)) {
                return result;
            }
            if ("task".equals(resultType)) {
                if (!McpProtocol.TOOLS_CALL.equals(method)) {
                    throw new McpProtocolException(-32603, "MCP task result is not valid for " + method, result);
                }
                if (invocation == null) {
                    throw new IllegalStateException("MCP task requires an active Turn invocation");
                }
                return awaitTask(result, invocation);
            }
            if (!"input_required".equals(resultType)) {
                if (resultType.isBlank()) {
                    throw new McpProtocolException(-32603, "MCP 2026-07-28 resultType is missing", result);
                }
                // Extension-defined result discriminators are preserved for forward compatibility.
                return result;
            }
            if (round == McpProtocol.MAX_MRTR_ROUNDS) {
                throw new IllegalStateException("MCP MRTR round-trip budget exceeded");
            }
            if (invocation == null) {
                throw new IllegalStateException("MCP operation requires interactive Turn input");
            }
            JsonNode requests = result.path("inputRequests");
            if (!requests.isObject() && !result.path("requestState").isTextual()) {
                throw new McpProtocolException(
                        -32603, "input_required result contains no input request or state", result);
            }
            ObjectNode responses = json.createObjectNode();
            if (requests.isObject()) {
                var fields = requests.fields();
                while (fields.hasNext()) {
                    var entry = fields.next();
                    responses.set(entry.getKey(), inputResolver.resolve(entry.getKey(), entry.getValue(), invocation));
                }
            }
            params.set("inputResponses", responses);
            if (result.path("requestState").isTextual()) {
                params.put("requestState", result.path("requestState").asText());
            }
        }
        throw new AssertionError("unreachable");
    }

    private JsonNode listAll(String method, String field) throws Exception {
        ArrayNode items = json.createArrayNode();
        Set<String> cursors = new HashSet<>();
        String cursor = null;
        long minimumTtl = Long.MAX_VALUE;
        String scope = "private";
        for (int page = 0; page < McpProtocol.MAX_PAGE_COUNT; page++) {
            ObjectNode params = json.createObjectNode();
            if (cursor != null) {
                params.put("cursor", cursor);
            }
            JsonNode result = invoke(method, params, null, null);
            requireComplete(result, method);
            JsonNode values = result.get(field);
            if (values == null || !values.isArray()) {
                throw new McpProtocolException(-32603, "MCP list result is missing " + field, result);
            }
            values.forEach(value -> items.add(value.deepCopy()));
            JsonNode ttlValue = result.get("ttlMs");
            long ttl = ttlValue != null && ttlValue.isIntegralNumber() && ttlValue.canConvertToLong()
                    ? ttlValue.asLong()
                    : -1;
            if (ttl < 0
                    || !Set.of("private", "public")
                            .contains(result.path("cacheScope").asText())) {
                throw new McpProtocolException(-32603, "MCP list result has invalid cache metadata", result);
            }
            minimumTtl = Math.min(minimumTtl, ttl);
            if (!"public".equals(result.path("cacheScope").asText())) {
                scope = "private";
            }
            String next = result.path("nextCursor").asText("");
            if (next.isEmpty()) {
                ObjectNode aggregate = json.createObjectNode();
                aggregate.put("resultType", "complete");
                aggregate.set("items", items);
                aggregate.put("ttlMs", minimumTtl == Long.MAX_VALUE ? 0 : minimumTtl);
                aggregate.put("cacheScope", scope);
                return aggregate;
            }
            if (!cursors.add(next)) {
                throw new McpProtocolException(-32603, "MCP pagination cursor loop", result);
            }
            cursor = next;
        }
        throw new IllegalStateException("MCP pagination exceeded 1000 pages");
    }

    private JsonNode awaitTask(JsonNode created, McpInvocation invocation) throws Exception {
        TaskMetadata seed = validateTask(created, null, null);
        String taskId = seed.taskId();
        Instant turnDeadline = clock.instant()
                .plus(invocation.context().config().sandboxPolicy().timeout());
        JsonNode state = created;
        Map<String, String> answered = new java.util.HashMap<>();
        int inputRounds = 0;
        try {
            for (int poll = 0; poll < McpProtocol.MAX_PAGE_COUNT; poll++) {
                TaskMetadata metadata = validateTask(state, taskId, seed.createdAt());
                Instant deadline = turnDeadline;
                if (metadata.ttlMillis() != null) {
                    Instant ttlDeadline = seed.createdAt().plusMillis(metadata.ttlMillis());
                    if (ttlDeadline.isBefore(deadline)) {
                        deadline = ttlDeadline;
                    }
                }
                String status = state.path("status").asText("");
                switch (status) {
                    case "completed" -> {
                        JsonNode result = state.get("result");
                        if (result == null || !result.isObject()) {
                            throw new McpProtocolException(-32603, "completed MCP task has no result", state);
                        }
                        requireComplete(result, "MCP task result");
                        return result.deepCopy();
                    }
                    case "failed" -> throw taskFailure(state);
                    case "cancelled" ->
                        throw new java.util.concurrent.CancellationException("MCP task was cancelled: " + taskId);
                    case "input_required" -> {
                        ObjectNode responses = json.createObjectNode();
                        JsonNode requests = state.path("inputRequests");
                        if (!requests.isObject()) {
                            throw new McpProtocolException(
                                    -32603, "input_required MCP task has no inputRequests", state);
                        }
                        var fields = requests.fields();
                        while (fields.hasNext()) {
                            var entry = fields.next();
                            String fingerprint = cacheKey(entry.getValue());
                            String previous = answered.putIfAbsent(entry.getKey(), fingerprint);
                            if (previous != null && !previous.equals(fingerprint)) {
                                throw new McpProtocolException(-32603, "MCP task reused an input request key", state);
                            }
                            if (previous == null) {
                                responses.set(
                                        entry.getKey(),
                                        inputResolver.resolve(entry.getKey(), entry.getValue(), invocation));
                            }
                        }
                        if (!responses.isEmpty()) {
                            if (++inputRounds > McpProtocol.MAX_MRTR_ROUNDS) {
                                throw new IllegalStateException("MCP task input round-trip budget exceeded");
                            }
                            ObjectNode update = json.createObjectNode().put("taskId", taskId);
                            update.set("inputResponses", responses);
                            requireComplete(
                                    invoke(McpProtocol.TASKS_UPDATE, update, null, invocation),
                                    McpProtocol.TASKS_UPDATE);
                        }
                    }
                    case "working" -> {}
                    default -> throw new McpProtocolException(-32603, "MCP task status is invalid", state);
                }
                Duration remaining = Duration.between(clock.instant(), deadline);
                if (remaining.isZero() || remaining.isNegative()) {
                    throw new IllegalStateException("MCP task exceeded the Turn timeout");
                }
                long requested = metadata.pollIntervalMillis();
                long sleepMillis = Math.min(Math.max(10, requested), remaining.toMillis());
                Thread.sleep(Math.max(1, sleepMillis));
                ObjectNode get = json.createObjectNode().put("taskId", taskId);
                state = invoke(McpProtocol.TASKS_GET, get, null, invocation);
                requireComplete(state, McpProtocol.TASKS_GET);
            }
            throw new IllegalStateException("MCP task polling limit exceeded");
        } catch (InterruptedException interrupted) {
            bestEffortCancelTask(taskId);
            Thread.currentThread().interrupt();
            throw interrupted;
        } catch (Exception failure) {
            bestEffortCancelTask(taskId);
            throw failure;
        }
    }

    private TaskMetadata validateTask(JsonNode value, String expectedTaskId, Instant expectedCreatedAt)
            throws McpProtocolException {
        String taskId = value.path("taskId").asText("").strip();
        if (taskId.isEmpty() || taskId.length() > 4_096 || expectedTaskId != null && !expectedTaskId.equals(taskId)) {
            throw new McpProtocolException(-32603, "MCP task id is invalid", value);
        }
        Instant createdAt;
        Instant updatedAt;
        try {
            createdAt = Instant.parse(value.path("createdAt").asText(""));
            updatedAt = Instant.parse(value.path("lastUpdatedAt").asText(""));
        } catch (RuntimeException failure) {
            throw new McpProtocolException(-32603, "MCP task timestamps are invalid", value);
        }
        if (expectedCreatedAt != null && !expectedCreatedAt.equals(createdAt) || updatedAt.isBefore(createdAt)) {
            throw new McpProtocolException(-32603, "MCP task timestamp ordering is invalid", value);
        }
        JsonNode ttl = value.get("ttlMs");
        if (ttl == null
                || !(ttl.isNull() || ttl.isIntegralNumber() && ttl.canConvertToLong())
                || ttl.isIntegralNumber() && ttl.asLong() < 0) {
            throw new McpProtocolException(-32603, "MCP task ttlMs is invalid", value);
        }
        JsonNode poll = value.get("pollIntervalMs");
        if (poll != null && (!poll.isIntegralNumber() || !poll.canConvertToLong() || poll.asLong() < 0)) {
            throw new McpProtocolException(-32603, "MCP task pollIntervalMs is invalid", value);
        }
        if (!Set.of("working", "input_required", "completed", "cancelled", "failed")
                .contains(value.path("status").asText())) {
            throw new McpProtocolException(-32603, "MCP task status is invalid", value);
        }
        return new TaskMetadata(
                taskId, createdAt, ttl.isNull() ? null : ttl.asLong(), poll == null ? 1_000 : poll.asLong());
    }

    private McpProtocolException taskFailure(JsonNode state) {
        JsonNode error = state.path("error");
        return new McpProtocolException(
                error.path("code").asInt(-32603), error.path("message").asText("MCP task failed"), error.get("data"));
    }

    private void bestEffortCancelTask(String taskId) {
        try {
            ObjectNode params = json.createObjectNode().put("taskId", taskId);
            ObjectNode request = codec.request(McpProtocol.TASKS_CANCEL, params);
            transport.exchange(
                    request,
                    McpHttpRoutingHeaders.forRequest(request, null, null),
                    Duration.ofSeconds(2),
                    ignored -> {});
        } catch (Exception ignored) {
        }
    }

    private JsonNode cached(String key, CheckedSupplier loader) throws Exception {
        CachedValue current = cache.get(key);
        Instant now = clock.instant();
        if (current != null && now.isBefore(current.expiresAt())) {
            return current.value().deepCopy();
        }
        JsonNode value = loader.get();
        long millis = Math.max(0, value.path("ttlMs").asLong(0));
        Duration ttl = Duration.ofMillis(millis);
        if (ttl.compareTo(MAX_CACHE_TTL) > 0) {
            ttl = MAX_CACHE_TTL;
        }
        if (!ttl.isZero()) {
            cache.put(key, new CachedValue(value.deepCopy(), now.plus(ttl)));
        } else {
            cache.remove(key);
        }
        return value.deepCopy();
    }

    private void acceptNotification(JsonNode notification) {
        invalidateFor(notification.path("method").asText(""));
    }

    private void invalidateFor(String method) {
        if (method.equals("notifications/tools/list_changed")) {
            cache.remove("tools");
        } else if (method.equals("notifications/resources/list_changed")) {
            cache.remove("resources");
            cache.remove("resourceTemplates");
        } else if (method.equals("notifications/prompts/list_changed")) {
            cache.remove("prompts");
        }
    }

    private void requireOpen() {
        if (closed.get()) {
            throw new IllegalStateException("MCP client is closed");
        }
    }

    private ObjectNode object(JsonNode value) {
        if (value == null || value.isNull()) {
            return json.createObjectNode();
        }
        if (!value.isObject()) {
            throw new IllegalArgumentException("MCP params must be an object");
        }
        return ((ObjectNode) value).deepCopy();
    }

    private void requireComplete(JsonNode value, String method) throws McpProtocolException {
        if (!"complete".equals(value.path("resultType").asText())) {
            throw new McpProtocolException(-32603, method + " did not return a complete result", value);
        }
    }

    private void requireCacheMetadata(JsonNode value, String method) throws McpProtocolException {
        if (!value.has("ttlMs")
                || !value.path("ttlMs").isIntegralNumber()
                || !value.path("ttlMs").canConvertToLong()
                || value.path("ttlMs").asLong() < 0
                || !Set.of("private", "public")
                        .contains(value.path("cacheScope").asText())) {
            throw new McpProtocolException(-32603, method + " returned invalid cache metadata", value);
        }
    }

    private String cacheKey(JsonNode value) {
        try {
            byte[] encoded = json.writeValueAsBytes(value);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(encoded));
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        } catch (com.fasterxml.jackson.core.JsonProcessingException failure) {
            throw new IllegalArgumentException("MCP cache key cannot be encoded", failure);
        }
    }

    private static void putStrings(ObjectNode target, String field, List<String> values) {
        if (values.isEmpty()) {
            return;
        }
        ArrayNode array = target.putArray(field);
        values.forEach(array::add);
    }

    private static String require(String value, String name) {
        String result = Objects.requireNonNull(value, name).strip();
        if (result.isEmpty()) {
            throw new IllegalArgumentException(name + " is blank");
        }
        return result;
    }

    private record CachedValue(JsonNode value, Instant expiresAt) {}

    private record TaskMetadata(String taskId, Instant createdAt, Long ttlMillis, long pollIntervalMillis) {}

    private record Subscription(JsonNode requestId, AtomicBoolean active, Thread thread) {}

    /**
     * 单订阅的目录、资源和任务过滤条件；集合在构造时固定。
     *
     * @param toolsListChanged 是否接收工具目录失效通知
     * @param promptsListChanged 是否接收 Prompt 目录失效通知
     * @param resourcesListChanged 是否接收 Resource 目录失效通知
     * @param resourceSubscriptions 资源订阅标识列表；null 归一为空列表
     * @param taskIds 任务扩展订阅标识；null 归一为空列表
     */
    public record SubscriptionFilter(
            boolean toolsListChanged,
            boolean promptsListChanged,
            boolean resourcesListChanged,
            List<String> resourceSubscriptions,
            List<String> taskIds) {
        /** 复制资源和任务过滤列表，防止订阅过程中被调用方改写。 */
        public SubscriptionFilter {
            resourceSubscriptions = validated(resourceSubscriptions, "resource URI");
            taskIds = validated(taskIds, "task id");
        }

        /** 创建仅监听工具、Prompt 和 Resource 目录变化的默认过滤器。 */
        public static SubscriptionFilter listChanges() {
            return new SubscriptionFilter(true, true, true, List.of(), List.of());
        }

        boolean accepts(JsonNode notification) {
            String method = notification.path("method").asText("");
            return switch (method) {
                case "notifications/subscriptions/acknowledged" -> true;
                case "notifications/tools/list_changed" -> toolsListChanged;
                case "notifications/prompts/list_changed" -> promptsListChanged;
                case "notifications/resources/list_changed" -> resourcesListChanged;
                case "notifications/resources/updated" ->
                    resourceSubscriptions.contains(
                            notification.path("params").path("uri").asText());
                case "notifications/tasks" ->
                    taskIds.contains(notification.path("params").path("taskId").asText());
                default -> false;
            };
        }

        boolean subsetOf(SubscriptionFilter requested) {
            return (!toolsListChanged || requested.toolsListChanged)
                    && (!promptsListChanged || requested.promptsListChanged)
                    && (!resourcesListChanged || requested.resourcesListChanged)
                    && requested.resourceSubscriptions.containsAll(resourceSubscriptions)
                    && requested.taskIds.containsAll(taskIds);
        }

        static SubscriptionFilter fromJson(JsonNode value) {
            if (!value.isObject()) {
                throw new IllegalStateException("MCP subscription acknowledgement omitted its filter");
            }
            Set<String> allowed = Set.of(
                    "toolsListChanged",
                    "promptsListChanged",
                    "resourcesListChanged",
                    "resourceSubscriptions",
                    "taskIds");
            var names = value.fieldNames();
            while (names.hasNext()) {
                if (!allowed.contains(names.next())) {
                    throw new IllegalStateException("MCP subscription acknowledgement has an unknown filter");
                }
            }
            return new SubscriptionFilter(
                    booleanField(value, "toolsListChanged"),
                    booleanField(value, "promptsListChanged"),
                    booleanField(value, "resourcesListChanged"),
                    stringArray(value.path("resourceSubscriptions")),
                    stringArray(value.path("taskIds")));
        }

        private static boolean booleanField(JsonNode value, String field) {
            JsonNode found = value.get(field);
            if (found == null) {
                return false;
            }
            if (!found.isBoolean()) {
                throw new IllegalStateException("MCP subscription filter boolean is invalid: " + field);
            }
            return found.asBoolean();
        }

        private static List<String> stringArray(JsonNode value) {
            if (value.isMissingNode()) {
                return List.of();
            }
            if (!value.isArray()) {
                throw new IllegalStateException("MCP subscription filter list is invalid");
            }
            ArrayList<String> result = new ArrayList<>();
            value.forEach(item -> {
                if (!item.isTextual()) {
                    throw new IllegalStateException("MCP subscription filter contains a non-string");
                }
                result.add(item.asText());
            });
            return List.copyOf(result);
        }

        private static List<String> validated(List<String> values, String name) {
            List<String> copy = values == null ? List.of() : List.copyOf(values);
            if (copy.size() > 1_000
                    || copy.stream().anyMatch(value -> value == null || value.isBlank() || value.length() > 4_096)) {
                throw new IllegalArgumentException("invalid MCP subscription " + name);
            }
            return copy;
        }
    }

    @FunctionalInterface
    private interface CheckedSupplier {
        JsonNode get() throws Exception;
    }
}
