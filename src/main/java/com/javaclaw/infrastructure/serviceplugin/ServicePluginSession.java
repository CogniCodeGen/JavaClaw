package com.javaclaw.infrastructure.serviceplugin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.BinaryNode;
import com.javaclaw.application.serviceplugin.ServicePluginInvocationPort;
import com.javaclaw.application.serviceplugin.ServicePluginInvocationPort.Event;
import com.javaclaw.application.serviceplugin.ServicePluginInvocationPort.Invocation;
import com.javaclaw.application.serviceplugin.ServicePluginInvocationPort.Response;
import com.javaclaw.application.serviceplugin.ServicePluginInvocationPort.ServicePluginException;
import com.javaclaw.application.serviceplugin.ServicePluginManagementApplicationService.EndpointConfiguration;
import com.javaclaw.application.serviceplugin.ServicePluginHostServiceRouter;
import com.javaclaw.application.serviceplugin.ServicePluginHostServiceRouter.HostServiceException;
import com.javaclaw.platform.execution.ManagedTaskExecutor;
import com.javaclaw.platform.execution.TaskHandle;
import com.javaclaw.platform.execution.TaskSpec;
import com.javaclaw.platform.execution.TriggerHandle;
import com.javaclaw.util.ProcessTerminator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/** One authenticated Desktop-to-plugin connection and all in-flight requests on it. */
final class ServicePluginSession implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(ServicePluginSession.class);
    private static final int REQUEST_CHUNK_BYTES = 4 * 1024 * 1024;
    private static final int LOG_LINES = 2_000;
    private static final int MAX_PENDING_REQUESTS = 256;

    private final ServicePluginDefinition definition;
    private final long desktopGeneration;
    private final Process process;
    private final Socket socket;
    private final ServicePluginWire.Codec codec;
    private final ManagedTaskExecutor tasks;
    private final ObjectMapper json;
    private final ServicePluginResourceBudget.Lease resourceLease;
    private final ServicePluginHostServiceRouter hostServices;
    private final Runnable disconnected;
    private final ConcurrentHashMap<String, Pending> pending = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, CompletableFuture<ServicePluginWire.HotConfigureResult>>
            hotConfigurations = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ReverseIncoming> reverseIncoming = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ReverseExecution> reverseExecutions = new ConcurrentHashMap<>();
    private final Set<String> services = ConcurrentHashMap.newKeySet();
    private final AtomicReference<Map<String, Object>> health = new AtomicReference<>(Map.of());
    private final AtomicLong lastPongNanos = new AtomicLong(System.nanoTime());
    private final AtomicBoolean accepting = new AtomicBoolean(true);
    private final AtomicBoolean expectedStop = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Deque<String> logs;
    private TaskHandle<Void> reader;
    private TaskHandle<Void> processMonitor;

    ServicePluginSession(ServicePluginDefinition definition, long desktopGeneration,
                         Process process, Socket socket, ServicePluginWire.Codec codec,
                         ManagedTaskExecutor tasks, ObjectMapper json,
                         ServicePluginResourceBudget.Lease resourceLease,
                         ServicePluginHostServiceRouter hostServices,
                         Deque<String> logs,
                         Runnable disconnected) {
        this.definition = definition;
        this.desktopGeneration = desktopGeneration;
        this.process = process;
        this.socket = socket;
        this.codec = codec;
        this.tasks = tasks;
        this.json = json;
        this.resourceLease = resourceLease;
        this.hostServices = java.util.Objects.requireNonNull(hostServices, "hostServices");
        this.logs = java.util.Objects.requireNonNull(logs, "logs");
        this.disconnected = disconnected;
    }

    void acceptCatalog(ServicePluginWire.Frame frame) throws IOException {
        if (frame.type() != ServicePluginWire.Type.SERVICE_CATALOG) {
            throw new IOException("服务插件没有在握手后发布服务目录");
        }
        JsonNode values = frame.payload() == null ? null : frame.payload().path("services");
        if (values != null && values.isArray()) {
            for (JsonNode value : values) {
                String id = value.path("id").asText("").strip();
                if (!id.isEmpty() && !services.add(id)) {
                    throw new IOException("服务插件发布了重复 service id: " + id);
                }
            }
        }
    }

    void startMonitors() {
        reader = tasks.submit(TaskSpec.io("读取服务插件 Socket " + definition.id()), context -> {
            try {
                ServicePluginWire.Frame frame;
                while (!closed.get() && (frame = codec.read()) != null) receive(frame);
                if (!closed.get()) connectionLost(new IOException("服务插件控制 Socket 已断开"));
            } catch (Throwable failure) {
                if (!closed.get()) connectionLost(failure);
            }
            return null;
        });
        processMonitor = tasks.submit(TaskSpec.process("监控服务插件进程 " + definition.id()), context -> {
            int exit = process.waitFor();
            addLog("process: exit=" + exit);
            if (!closed.get()) connectionLost(new IOException("服务插件进程退出: " + exit));
            return null;
        });
    }

    Invocation invoke(String serviceId, String operation, String contentType, byte[] payload,
                      Duration timeout, Consumer<Event> events) {
        if (!accepting.get() || closed.get() || !process.isAlive()) {
            throw new ServicePluginException("service_unavailable", "服务插件当前不可用", true);
        }
        if (!services.contains(serviceId)) {
            throw new ServicePluginException("service_not_found", "服务插件未注册服务: " + serviceId, false);
        }
        int configuredLimit = (int) Math.min(MAX_PENDING_REQUESTS,
                Math.max(16L, (long) definition.resources().ioConcurrency() * 2));
        if (pending.size() >= configuredLimit) {
            throw new ServicePluginException("resource_exhausted",
                    "服务插件内部请求队列已满", true);
        }
        byte[] body = payload == null ? new byte[0] : payload.clone();
        if (body.length > ServicePluginWire.MAX_REQUEST_BYTES) {
            throw new ServicePluginException("request_too_large", "内部服务请求超过 32 MiB", false);
        }
        Duration effectiveTimeout = timeout == null || timeout.isZero()
                ? Duration.ofMinutes(10) : timeout;
        if (effectiveTimeout.isNegative()) throw new IllegalArgumentException("timeout 不能为负数");
        String requestId = UUID.randomUUID().toString();
        Pending state = new Pending(requestId, events == null ? ignored -> { } : events,
                new CompletableFuture<>(), new AtomicLong(), new AtomicBoolean(), new AtomicReference<>(),
                new ByteArrayOutputStream(), new AtomicReference<>(), new AtomicReference<>());
        if (pending.putIfAbsent(requestId, state) != null) throw new AssertionError("UUID collision");
        TriggerHandle timeoutTrigger = tasks.scheduleTrigger(effectiveTimeout, () -> {
            Pending removed = pending.remove(requestId);
            if (removed == null || !removed.terminal().compareAndSet(false, true)) return;
            sendCancel(requestId);
            removed.completion().completeExceptionally(new ServicePluginException(
                    "service_timeout", "服务插件请求超时", true));
        });
        state.timeout().set(timeoutTrigger);
        long deadline = System.currentTimeMillis() + effectiveTimeout.toMillis();
        try {
            writeRequestChunks(state, serviceId, operation,
                    contentType == null ? "application/octet-stream" : contentType, body, deadline);
        } catch (Throwable failure) {
            finishExceptionally(state, new ServicePluginException(
                    "service_connection_failed", "无法发送服务插件请求", true, new byte[0], failure));
        }
        return new Invocation() {
            @Override public String requestId() { return requestId; }
            @Override public CompletableFuture<Response> completion() { return state.completion(); }
            @Override public boolean cancel() { return cancelRequest(state); }
        };
    }

    void hotConfigure(ServicePluginDefinition candidate, Duration timeout) throws Exception {
        if (!definition.id().equals(candidate.id())
                || !definition.version().equals(candidate.version())) {
            throw new IllegalArgumentException("热配置不能替换服务插件身份");
        }
        if (!accepting.get() || closed.get() || !process.isAlive()) {
            throw new ServicePluginException("service_unavailable", "服务插件当前不可用", true);
        }
        List<ServicePluginWire.Endpoint> endpoints = candidate.endpoints().stream()
                .map(ServicePluginSession::wireEndpoint).toList();
        ServicePluginWire.HotConfigure payload = new ServicePluginWire.HotConfigure(
                candidate.config(), endpoints);
        String requestId = "hot-config:" + UUID.randomUUID();
        CompletableFuture<ServicePluginWire.HotConfigureResult> completion = new CompletableFuture<>();
        if (hotConfigurations.putIfAbsent(requestId, completion) != null) {
            throw new AssertionError("UUID collision");
        }
        try {
            codec.write(new ServicePluginWire.Frame(ServicePluginWire.PROTOCOL_MAJOR,
                    ServicePluginWire.PROTOCOL_MINOR, ServicePluginWire.Type.HOT_CONFIGURE,
                    requestId, desktopGeneration, definition.id(), definition.version(),
                    "", "", 0, 0, "application/json", json.valueToTree(payload),
                    true, "", ""));
            Duration effective = timeout == null || timeout.isZero()
                    ? Duration.ofSeconds(10) : timeout;
            ServicePluginWire.HotConfigureResult result = completion.get(
                    Math.max(1, effective.toMillis()), java.util.concurrent.TimeUnit.MILLISECONDS);
            if (!result.success()) {
                throw new ServicePluginException("hot_configuration_failed",
                        result.message().isBlank() ? "服务插件热配置失败" : result.message(), false);
            }
        } finally {
            hotConfigurations.remove(requestId, completion);
        }
    }

    private static ServicePluginWire.Endpoint wireEndpoint(EndpointConfiguration endpoint) {
        return new ServicePluginWire.Endpoint(endpoint.id(), endpoint.protocol().name(),
                endpoint.bindAddress(), endpoint.port(), endpoint.tlsEnabled(),
                endpoint.allowInsecureLan(), endpoint.keyStorePath() == null ? ""
                        : endpoint.keyStorePath().toAbsolutePath().normalize().toString(),
                endpoint.keyStorePassword(), endpoint.apiKey(), endpoint.requestsPerMinute(),
                endpoint.tokensPerMinute(), endpoint.maxConcurrent(), endpoint.maxConnections(),
                endpoint.maxRequestBytes(), endpoint.requestTimeoutSeconds());
    }

    boolean cancel(String requestId) {
        Pending state = pending.get(requestId);
        return state != null && cancelRequest(state);
    }

    void ping(long sequence) {
        if (closed.get()) return;
        try {
            codec.write(ServicePluginWire.Frame.control(ServicePluginWire.Type.PING,
                    desktopGeneration, definition.id(), definition.version(),
                    json.valueToTree(Map.of("sequence", sequence,
                            "sentAt", System.currentTimeMillis()))));
            if (sequence % 5 == 0) {
                codec.write(new ServicePluginWire.Frame(ServicePluginWire.PROTOCOL_MAJOR,
                        ServicePluginWire.PROTOCOL_MINOR, ServicePluginWire.Type.HEALTH,
                        "health-" + sequence, desktopGeneration, definition.id(), definition.version(),
                        "", "", 0, 0, "application/json", json.nullNode(), false, "", ""));
            }
        } catch (IOException failure) {
            connectionLost(failure);
        }
    }

    boolean heartbeatExpired(Duration duration) {
        return System.nanoTime() - lastPongNanos.get() > duration.toNanos();
    }

    void drain() {
        if (!accepting.compareAndSet(true, false)) return;
        try {
            codec.write(ServicePluginWire.Frame.control(ServicePluginWire.Type.DRAIN,
                    desktopGeneration, definition.id(), definition.version(), json.nullNode()));
        } catch (IOException failure) {
            log.debug("发送服务插件排空命令失败: id={}", definition.id(), failure);
        }
    }

    void shutdownGracefully(Duration drainTimeout, Duration exitTimeout) {
        expectedStop.set(true);
        drain();
        long deadline = System.nanoTime() + drainTimeout.toNanos();
        while (!pending.isEmpty() && System.nanoTime() < deadline) {
            try { Thread.sleep(25); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); break; }
        }
        try {
            codec.write(ServicePluginWire.Frame.control(ServicePluginWire.Type.SHUTDOWN,
                    desktopGeneration, definition.id(), definition.version(), json.nullNode()));
            if (!process.waitFor(exitTimeout.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS)) {
                forceAndAwaitExit();
            }
        } catch (InterruptedException interrupted) {
            forceAndAwaitExit();
            Thread.currentThread().interrupt();
        } catch (IOException failure) {
            forceAndAwaitExit();
        } finally {
            close();
        }
    }

    ServicePluginDefinition definition() { return definition; }
    long pid() { return process.pid(); }
    Process process() { return process; }
    boolean alive() { return process.isAlive() && !closed.get(); }
    boolean expectedStop() { return expectedStop.get(); }
    int activeRequests() { return healthCount("activeRequests", pending.size()); }
    int queuedRequests() { return healthCount("queuedRequests", 0); }
    Set<String> services() { return Set.copyOf(services); }
    Map<String, Object> health() { return health.get(); }
    List<String> recentLogs(int max) {
        synchronized (logs) {
            List<String> values = new ArrayList<>(logs);
            return values.subList(Math.max(0, values.size() - Math.max(0, max)), values.size());
        }
    }

    private void writeRequestChunks(Pending state, String serviceId, String operation,
                                    String contentType, byte[] body, long deadline) throws IOException {
        int count = Math.max(1, (body.length + REQUEST_CHUNK_BYTES - 1) / REQUEST_CHUNK_BYTES);
        for (int sequence = 0; sequence < count; sequence++) {
            int start = sequence * REQUEST_CHUNK_BYTES;
            int end = Math.min(body.length, start + REQUEST_CHUNK_BYTES);
            byte[] chunk = java.util.Arrays.copyOfRange(body, start, end);
            codec.write(new ServicePluginWire.Frame(ServicePluginWire.PROTOCOL_MAJOR,
                    ServicePluginWire.PROTOCOL_MINOR, ServicePluginWire.Type.REQUEST,
                    state.requestId(), desktopGeneration, definition.id(), definition.version(),
                    serviceId, operation, sequence, deadline, contentType,
                    BinaryNode.valueOf(chunk), sequence == count - 1, "", ""));
        }
    }

    private void receive(ServicePluginWire.Frame frame) throws IOException {
        if (frame.desktopGeneration() != desktopGeneration
                || !definition.id().equals(frame.pluginId())
                || !definition.version().equals(frame.pluginVersion())) {
            throw new IOException("服务插件帧身份不匹配");
        }
        switch (frame.type()) {
            case PONG -> lastPongNanos.set(System.nanoTime());
            case HOT_CONFIGURE_ACK -> hotConfigureAcknowledged(frame);
            case HEALTH -> {
                lastPongNanos.set(System.nanoTime());
                @SuppressWarnings("unchecked") Map<String, Object> value = frame.payload() == null
                        ? Map.of() : json.convertValue(frame.payload(), Map.class);
                health.set(Map.copyOf(value));
            }
            case EVENT -> event(frame);
            case RESPONSE, ERROR -> response(frame);
            case REQUEST -> reverseRequest(frame);
            case CANCEL -> cancelReverse(frame.requestId());
            default -> throw new IOException("服务插件返回了意外帧: " + frame.type());
        }
    }

    private void hotConfigureAcknowledged(ServicePluginWire.Frame frame) {
        CompletableFuture<ServicePluginWire.HotConfigureResult> completion =
                hotConfigurations.remove(frame.requestId());
        if (completion == null) return;
        try {
            completion.complete(json.treeToValue(
                    frame.payload(), ServicePluginWire.HotConfigureResult.class));
        } catch (Exception invalid) {
            completion.completeExceptionally(invalid);
        }
    }

    private void reverseRequest(ServicePluginWire.Frame frame) {
        if (!frame.requestId().startsWith("plugin:")) {
            reverseError(frame, "invalid_reverse_request", "反向请求 ID 前缀无效", new byte[0]);
            return;
        }
        if (reverseIncoming.size() >= 32 && !reverseIncoming.containsKey(frame.requestId())) {
            reverseError(frame, "resource_exhausted", "反向请求队列已满", new byte[0]);
            return;
        }
        ReverseIncoming aggregate;
        if (frame.sequence() == 0) {
            aggregate = new ReverseIncoming(frame.serviceId(), frame.operation(), frame.contentType(),
                    frame.deadlineEpochMilli(), new ByteArrayOutputStream(), 0);
            if (reverseIncoming.putIfAbsent(frame.requestId(), aggregate) != null
                    || reverseExecutions.containsKey(frame.requestId())) {
                reverseError(frame, "duplicate_request", "反向请求 ID 已存在", new byte[0]);
                return;
            }
        } else {
            aggregate = reverseIncoming.get(frame.requestId());
            if (aggregate == null) {
                reverseError(frame, "invalid_chunk", "反向请求分块缺少起始帧", new byte[0]);
                return;
            }
        }
        try {
            synchronized (aggregate) {
                if (frame.sequence() != aggregate.nextSequence
                        || !frame.serviceId().equals(aggregate.serviceId)
                        || !frame.operation().equals(aggregate.operation)
                        || !frame.contentType().equals(aggregate.contentType)
                        || frame.deadlineEpochMilli() != aggregate.deadlineEpochMilli) {
                    throw new IOException("反向请求分块顺序或元数据不一致");
                }
                byte[] chunk = bytes(frame);
                if (aggregate.body.size() + (long) chunk.length > ServicePluginWire.MAX_REQUEST_BYTES) {
                    throw new IOException("反向请求超过 32 MiB");
                }
                aggregate.body.write(chunk);
                aggregate.nextSequence++;
                if (!frame.terminal()) return;
                reverseIncoming.remove(frame.requestId(), aggregate);
                dispatchReverse(frame, aggregate.body.toByteArray());
            }
        } catch (Exception invalid) {
            reverseIncoming.remove(frame.requestId(), aggregate);
            reverseError(frame, "invalid_reverse_request", safeMessage(invalid), new byte[0]);
        }
    }

    private void dispatchReverse(ServicePluginWire.Frame frame, byte[] payload) {
        Instant deadline = frame.deadlineEpochMilli() <= 0
                ? Instant.MAX : Instant.ofEpochMilli(frame.deadlineEpochMilli());
        if (!Instant.MAX.equals(deadline) && !Instant.now().isBefore(deadline)) {
            reverseError(frame, "host_deadline_exceeded", "Desktop 服务请求已超时", new byte[0]);
            return;
        }
        ReverseExecution state = new ReverseExecution(frame, new AtomicBoolean(),
                new AtomicBoolean(), new AtomicLong(), new AtomicReference<>());
        if (reverseExecutions.putIfAbsent(frame.requestId(), state) != null) {
            reverseError(frame, "duplicate_request", "反向请求 ID 已存在", new byte[0]);
            return;
        }
        Duration remaining = Instant.MAX.equals(deadline) ? Duration.ZERO
                : Duration.between(Instant.now(), deadline);
        if (!remaining.isZero() && remaining.isNegative()) remaining = Duration.ofNanos(1);
        try {
            TaskSpec spec = TaskSpec.io("服务插件反向调用 " + definition.id() + "/" + frame.serviceId())
                    .withTimeout(remaining);
            TaskHandle<Void> handle = tasks.submit(spec, context -> {
                try {
                    ServicePluginHostServiceRouter.Response response = hostServices.invoke(
                            new ServicePluginHostServiceRouter.Request(definition.id(),
                                    definition.permissions(), frame.requestId(), frame.serviceId(),
                                    frame.operation(), frame.contentType(), payload, deadline),
                            () -> state.cancelled().get()
                                    || context.cancellation().isCancellationRequested(),
                            event -> reverseEvent(state, event));
                    if (response == null) throw new HostServiceException(
                            "host_missing_response", "Desktop 服务没有返回响应");
                    reverseTerminal(state, ServicePluginWire.Type.RESPONSE, "", "",
                            response.contentType(), response.payload());
                } catch (HostServiceException failure) {
                    reverseTerminal(state, ServicePluginWire.Type.ERROR, failure.code(),
                            safeMessage(failure), "application/octet-stream", failure.payload());
                } catch (Throwable failure) {
                    String code = (!Instant.MAX.equals(deadline) && !Instant.now().isBefore(deadline))
                            ? "host_deadline_exceeded"
                            : state.cancelled().get() || context.cancellation().isCancellationRequested()
                            ? "cancelled" : "host_service_failure";
                    reverseTerminal(state, ServicePluginWire.Type.ERROR, code,
                            safeMessage(failure), "application/json", new byte[0]);
                }
                return null;
            });
            state.task().set(handle);
            handle.completion().whenComplete((ignored, failure) -> {
                if (failure != null) {
                    String code = (!Instant.MAX.equals(deadline) && !Instant.now().isBefore(deadline))
                            ? "host_deadline_exceeded" : state.cancelled().get()
                            ? "cancelled" : "host_service_failure";
                    reverseTerminal(state, ServicePluginWire.Type.ERROR, code,
                            safeMessage(failure), "application/json", new byte[0]);
                }
            });
        } catch (RuntimeException rejected) {
            reverseTerminal(state, ServicePluginWire.Type.ERROR, "resource_exhausted",
                    safeMessage(rejected), "application/json", new byte[0]);
        }
    }

    private void reverseEvent(ReverseExecution state, ServicePluginHostServiceRouter.Event event) {
        if (state.cancelled().get() || state.terminal().get()) return;
        byte[] payload = event.payload();
        if (payload.length > REQUEST_CHUNK_BYTES) {
            reverseTerminal(state, ServicePluginWire.Type.ERROR, "event_too_large",
                    "Desktop 服务事件超过单帧限制", "application/json", new byte[0]);
            return;
        }
        reverseFrame(state, ServicePluginWire.Type.EVENT, false, "", "",
                event.contentType(), payload);
    }

    private void reverseTerminal(ReverseExecution state, ServicePluginWire.Type type,
                                 String code, String message, String contentType, byte[] payload) {
        if (!state.terminal().compareAndSet(false, true)) return;
        byte[] value = payload == null ? new byte[0] : payload;
        if (value.length > ServicePluginWire.MAX_REQUEST_BYTES) {
            type = ServicePluginWire.Type.ERROR;
            code = "response_too_large";
            message = "Desktop 服务响应超过 32 MiB";
            contentType = "application/json";
            value = new byte[0];
        }
        int count = Math.max(1, (value.length + REQUEST_CHUNK_BYTES - 1) / REQUEST_CHUNK_BYTES);
        for (int index = 0; index < count; index++) {
            int start = index * REQUEST_CHUNK_BYTES;
            int end = Math.min(value.length, start + REQUEST_CHUNK_BYTES);
            reverseFrame(state, type, index == count - 1, code, message, contentType,
                    java.util.Arrays.copyOfRange(value, start, end));
        }
        reverseExecutions.remove(state.request().requestId(), state);
    }

    private void reverseFrame(ReverseExecution state, ServicePluginWire.Type type,
                              boolean terminal, String code, String message,
                              String contentType, byte[] payload) {
        ServicePluginWire.Frame request = state.request();
        try {
            codec.write(new ServicePluginWire.Frame(ServicePluginWire.PROTOCOL_MAJOR,
                    ServicePluginWire.PROTOCOL_MINOR, type, request.requestId(), desktopGeneration,
                    definition.id(), definition.version(), request.serviceId(), request.operation(),
                    state.sequence().getAndIncrement(), request.deadlineEpochMilli(), contentType,
                    BinaryNode.valueOf(payload == null ? new byte[0] : payload), terminal,
                    code, message));
        } catch (IOException failure) {
            connectionLost(failure);
        }
    }

    private void reverseError(ServicePluginWire.Frame request, String code,
                              String message, byte[] payload) {
        ReverseExecution state = new ReverseExecution(request, new AtomicBoolean(),
                new AtomicBoolean(), new AtomicLong(), new AtomicReference<>());
        reverseTerminal(state, ServicePluginWire.Type.ERROR, code, message,
                "application/json", payload);
    }

    private void cancelReverse(String requestId) {
        reverseIncoming.remove(requestId);
        ReverseExecution state = reverseExecutions.get(requestId);
        if (state == null) return;
        state.cancelled().set(true);
        TaskHandle<Void> task = state.task().get();
        if (task != null) task.cancel();
        reverseTerminal(state, ServicePluginWire.Type.ERROR, "cancelled",
                "Desktop 服务请求已取消", "application/json", new byte[0]);
    }

    private void event(ServicePluginWire.Frame frame) throws IOException {
        Pending state = pending.get(frame.requestId());
        if (state == null || state.terminal().get()) return;
        long expected = state.nextSequence().getAndIncrement();
        if (frame.sequence() != expected || frame.terminal()) {
            throw new IOException("服务插件流式事件序号或终态标记无效");
        }
        try { state.events().accept(new Event(frame.sequence(), frame.contentType(), bytes(frame))); }
        catch (Throwable callbackFailure) { cancelRequest(state); }
    }

    private void response(ServicePluginWire.Frame frame) throws IOException {
        Pending state = pending.get(frame.requestId());
        if (state == null) return;
        if (frame.sequence() != state.nextSequence().getAndIncrement()) {
            throw new IOException("服务插件响应分块序号无效");
        }
        byte[] chunk = bytes(frame);
        synchronized (state.responseBody()) {
            if (state.responseBody().size() + (long) chunk.length > ServicePluginWire.MAX_REQUEST_BYTES) {
                cancelRequest(state);
                throw new IOException("服务插件响应超过 32 MiB");
            }
            String contentType = frame.contentType();
            String previous = state.responseContentType().get();
            if (previous == null) state.responseContentType().set(contentType);
            else if (!previous.equals(contentType)) throw new IOException("服务插件响应分块类型不一致");
            ServicePluginWire.Type responseType = state.responseType().get();
            if (responseType == null) state.responseType().set(frame.type());
            else if (responseType != frame.type()) throw new IOException("服务插件响应分块终态类型不一致");
            state.responseBody().write(chunk);
        }
        if (!frame.terminal()) return;
        pending.remove(frame.requestId(), state);
        if (!state.terminal().compareAndSet(false, true)) return;
        cancelTimeout(state);
        byte[] payload;
        synchronized (state.responseBody()) { payload = state.responseBody().toByteArray(); }
        if (frame.type() == ServicePluginWire.Type.RESPONSE) {
            state.completion().complete(new Response(state.responseContentType().get(), payload));
        } else {
            state.completion().completeExceptionally(new ServicePluginException(
                    frame.errorCode(), frame.errorMessage(), isRetryable(frame.errorCode()), payload, null));
        }
    }

    private byte[] bytes(ServicePluginWire.Frame frame) throws IOException {
        JsonNode node = frame.payload();
        if (node == null || node.isNull()) return new byte[0];
        return node.isBinary() || node.isTextual()
                ? node.binaryValue() : json.writeValueAsBytes(node);
    }

    private boolean cancelRequest(Pending state) {
        if (!pending.remove(state.requestId(), state) || !state.terminal().compareAndSet(false, true)) return false;
        cancelTimeout(state);
        sendCancel(state.requestId());
        state.completion().completeExceptionally(new ServicePluginException(
                "cancelled", "服务插件请求已取消", false));
        return true;
    }

    private void sendCancel(String requestId) {
        try {
            codec.write(new ServicePluginWire.Frame(ServicePluginWire.PROTOCOL_MAJOR,
                    ServicePluginWire.PROTOCOL_MINOR, ServicePluginWire.Type.CANCEL,
                    requestId, desktopGeneration, definition.id(), definition.version(),
                    "", "", 0, 0, "application/json", json.nullNode(), false, "", ""));
        } catch (IOException failure) { connectionLost(failure); }
    }

    private void finishExceptionally(Pending state, Throwable failure) {
        pending.remove(state.requestId(), state);
        if (!state.terminal().compareAndSet(false, true)) return;
        cancelTimeout(state);
        state.completion().completeExceptionally(failure);
    }

    private static void cancelTimeout(Pending state) {
        TriggerHandle timeout = state.timeout().getAndSet(null);
        if (timeout != null) timeout.cancel();
    }

    private void connectionLost(Throwable failure) {
        if (!closed.compareAndSet(false, true)) return;
        accepting.set(false);
        String message = failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
        addLog("control: " + message);
        pending.values().forEach(state -> finishExceptionally(state,
                new ServicePluginException("service_process_lost",
                        "服务插件进程连接已丢失", true, new byte[0], failure)));
        pending.clear();
        hotConfigurations.values().forEach(value -> value.completeExceptionally(failure));
        hotConfigurations.clear();
        reverseExecutions.values().forEach(state -> {
            state.cancelled().set(true);
            TaskHandle<Void> task = state.task().get();
            if (task != null) task.cancel();
        });
        reverseExecutions.clear();
        reverseIncoming.clear();
        if (process.isAlive()) {
            ProcessTerminator.destroyTreeForcibly(process);
            try { process.waitFor(2, java.util.concurrent.TimeUnit.SECONDS); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        }
        closeResources();
        disconnected.run();
    }

    private void addLog(String value) {
        synchronized (logs) {
            if (logs.size() >= LOG_LINES) logs.removeFirst();
            logs.addLast(value);
        }
    }

    private int healthCount(String key, int fallback) {
        Object value = health.get().get(key);
        if (value instanceof Number number) return Math.max(0, number.intValue());
        return fallback;
    }

    private static boolean isRetryable(String code) {
        return Set.of("service_process_lost", "service_unavailable", "service_timeout",
                "service_draining", "resource_exhausted").contains(code);
    }

    private static String safeMessage(Throwable failure) {
        return failure.getMessage() == null
                ? failure.getClass().getSimpleName() : failure.getMessage();
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        expectedStop.set(true);
        accepting.set(false);
        pending.values().forEach(state -> finishExceptionally(state,
                new ServicePluginException("service_process_lost", "服务插件已停止", true)));
        pending.clear();
        hotConfigurations.values().forEach(value -> value.completeExceptionally(
                new ServicePluginException("service_process_lost", "服务插件已停止", true)));
        hotConfigurations.clear();
        reverseExecutions.values().forEach(state -> {
            state.cancelled().set(true);
            TaskHandle<Void> task = state.task().get();
            if (task != null) task.cancel();
        });
        reverseExecutions.clear();
        reverseIncoming.clear();
        closeResources();
    }

    private void closeResources() {
        ServicePluginResourceCloser.close(codec);
        ServicePluginResourceCloser.close(socket);
        if (process.isAlive()) {
            addLog("process: 强制终止后仍未退出，保留资源预算以阻止重叠启动");
            process.onExit().whenComplete((ignored, failure) -> resourceLease.close());
        } else {
            resourceLease.close();
        }
    }

    private void forceAndAwaitExit() {
        ProcessTerminator.destroyTreeForcibly(process);
        try { process.waitFor(2, java.util.concurrent.TimeUnit.SECONDS); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
    }

    private record Pending(String requestId, Consumer<Event> events,
                           CompletableFuture<Response> completion, AtomicLong nextSequence,
                           AtomicBoolean terminal, AtomicReference<TriggerHandle> timeout,
                           ByteArrayOutputStream responseBody,
                           AtomicReference<String> responseContentType,
                           AtomicReference<ServicePluginWire.Type> responseType) { }

    private record ReverseExecution(ServicePluginWire.Frame request,
                                    AtomicBoolean cancelled, AtomicBoolean terminal,
                                    AtomicLong sequence,
                                    AtomicReference<TaskHandle<Void>> task) { }

    private static final class ReverseIncoming {
        private final String serviceId;
        private final String operation;
        private final String contentType;
        private final long deadlineEpochMilli;
        private final ByteArrayOutputStream body;
        private long nextSequence;

        private ReverseIncoming(String serviceId, String operation, String contentType,
                                long deadlineEpochMilli, ByteArrayOutputStream body,
                                long nextSequence) {
            this.serviceId = serviceId;
            this.operation = operation;
            this.contentType = contentType;
            this.deadlineEpochMilli = deadlineEpochMilli;
            this.body = body;
            this.nextSequence = nextSequence;
        }
    }
}
