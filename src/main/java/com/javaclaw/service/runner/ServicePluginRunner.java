package com.javaclaw.service.runner;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.BinaryNode;
import com.javaclaw.service.api.CancellationToken;
import com.javaclaw.service.api.DesktopServiceClient;
import com.javaclaw.service.api.DesktopServiceClient.DesktopServiceException;
import com.javaclaw.service.api.ExternalEndpointDescriptor;
import com.javaclaw.service.api.InternalServiceRegistry;
import com.javaclaw.service.api.PluginConfig;
import com.javaclaw.service.api.PluginLogger;
import com.javaclaw.service.api.PluginNetworkContext;
import com.javaclaw.service.api.PluginResourceContext;
import com.javaclaw.service.api.Registration;
import com.javaclaw.service.api.ServiceDescriptor;
import com.javaclaw.service.api.ServiceInvocation;
import com.javaclaw.service.api.ServicePlugin;
import com.javaclaw.service.api.ServicePluginContext;
import com.javaclaw.service.api.ServiceRequestHandler;
import com.javaclaw.service.api.ServiceResponseChannel;

import java.io.IOException;
import java.io.ByteArrayOutputStream;
import java.net.InetAddress;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.UUID;

final class ServicePluginRunner implements AutoCloseable {
    private static final long HEARTBEAT_WARN_NANOS = Duration.ofSeconds(10).toNanos();
    private static final long HEARTBEAT_EXIT_NANOS = Duration.ofSeconds(15).toNanos();
    private static final Duration STOP_TIMEOUT = Duration.ofSeconds(5);
    private static final int RESPONSE_CHUNK_BYTES = 4 * 1024 * 1024;

    private final Path pluginJar;
    private final String pluginId;
    private final String pluginVersion;
    private final long desktopGeneration;
    private final ServicePluginWire.Configure configure;
    private final ServicePluginWire.Codec codec;
    private final ObjectMapper json;
    private final PluginLogger log;
    private final boolean enforceLease;
    private final Map<String, RegisteredService> services = new ConcurrentHashMap<>();
    private final Map<String, ActiveRequest> active = new ConcurrentHashMap<>();
    private final Map<String, IncomingRequest> incoming = new ConcurrentHashMap<>();
    private final Map<String, ReversePending> reversePending = new ConcurrentHashMap<>();
    private final AtomicBoolean accepting = new AtomicBoolean(true);
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicLong lastHeartbeat = new AtomicLong(System.nanoTime());
    private final AtomicInteger runningRequests = new AtomicInteger();
    private final AtomicReference<Thread> controlThread = new AtomicReference<>();
    private final AtomicReference<Map<String, String>> pluginConfig;
    private final Semaphore requestAdmissions;
    private final int maxAdmittedRequests;
    private URLClassLoader classLoader;
    private ExecutorService requests;
    private RunnerExecutor executor;
    private ServicePlugin plugin;
    private ExternalEndpointManager endpoints;
    private Thread leaseMonitor;

    ServicePluginRunner(Path pluginJar, String pluginId, String pluginVersion,
                        long desktopGeneration, ServicePluginWire.Configure configure,
                        ServicePluginWire.Codec codec, ObjectMapper json) {
        this(pluginJar, pluginId, pluginVersion, desktopGeneration, configure, codec, json, true);
    }

    ServicePluginRunner(Path pluginJar, String pluginId, String pluginVersion,
                        long desktopGeneration, ServicePluginWire.Configure configure,
                        ServicePluginWire.Codec codec, ObjectMapper json, boolean enforceLease) {
        this.pluginJar = pluginJar;
        this.pluginId = pluginId;
        this.pluginVersion = pluginVersion;
        this.desktopGeneration = desktopGeneration;
        this.configure = configure;
        this.codec = codec;
        this.json = json;
        log = new RunnerLogger(pluginId);
        this.enforceLease = enforceLease;
        pluginConfig = new AtomicReference<>(configure.config());
        requestAdmissions = new Semaphore(Math.max(1, configure.resources().ioConcurrency()), true);
        maxAdmittedRequests = (int) Math.min(256L,
                Math.max(16L, (long) configure.resources().ioConcurrency() * 2));
    }

    void start() throws Exception {
        classLoader = new PluginClassLoader(classpath(), ServicePlugin.class.getClassLoader());
        requests = Executors.newThreadPerTaskExecutor(RunnerExecutor.withContextClassLoader(
                Thread.ofVirtual().name("service-plugin-request-", 0).factory(), classLoader));
        executor = new RunnerExecutor(classLoader);
        endpoints = new ExternalEndpointManager(
                configure.endpoints(), requests, executor, json, log);
        plugin = withPluginContext(() -> {
            Class<?> entry = Class.forName(configure.mainClass(), true, classLoader);
            Object value = entry.getDeclaredConstructor().newInstance();
            if (!(value instanceof ServicePlugin servicePlugin)) {
                throw new IllegalStateException("service entry does not implement ServicePlugin");
            }
            servicePlugin.start(new Context());
            return servicePlugin;
        });
        sendCatalog();
        if (enforceLease) {
            leaseMonitor = Thread.ofPlatform().name("service-lease-" + pluginId).start(this::monitorLease);
        }
    }

    void runControlLoop() throws IOException {
        controlThread.set(Thread.currentThread());
        ServicePluginWire.Frame frame;
        while (!closed.get() && (frame = codec.read()) != null) {
            if (frame.desktopGeneration() != desktopGeneration
                    || !pluginId.equals(frame.pluginId())
                    || !pluginVersion.equals(frame.pluginVersion())) {
                throw new SecurityException("service-plugin frame identity mismatch");
            }
            switch (frame.type()) {
                case PING -> heartbeat(frame);
                case HOT_CONFIGURE -> hotConfigure(frame);
                case REQUEST -> request(frame);
                case CANCEL -> {
                    if (reversePending.containsKey(frame.requestId())) reverseCancelled(frame.requestId());
                    else cancel(frame.requestId());
                }
                case EVENT -> reverseEvent(frame);
                case RESPONSE, ERROR -> reverseResponse(frame);
                case HEALTH -> health(frame);
                case DRAIN -> drain();
                case SHUTDOWN -> { drain(); return; }
                default -> throw new IOException("unexpected Desktop frame: " + frame.type());
            }
        }
        accepting.set(false);
    }

    private void heartbeat(ServicePluginWire.Frame frame) throws IOException {
        lastHeartbeat.set(System.nanoTime());
        codec.write(ServicePluginWire.Frame.control(ServicePluginWire.Type.PONG,
                desktopGeneration, pluginId, pluginVersion, frame.payload()));
    }

    private void hotConfigure(ServicePluginWire.Frame frame) throws IOException {
        ServicePluginWire.HotConfigureResult result;
        try {
            ServicePluginWire.HotConfigure value = json.treeToValue(
                    frame.payload(), ServicePluginWire.HotConfigure.class);
            endpoints.reconfigure(value.endpoints());
            pluginConfig.set(value.config());
            result = new ServicePluginWire.HotConfigureResult(true, "");
        } catch (Exception failure) {
            result = new ServicePluginWire.HotConfigureResult(false, safeMessage(failure));
        }
        codec.write(new ServicePluginWire.Frame(ServicePluginWire.PROTOCOL_MAJOR,
                ServicePluginWire.PROTOCOL_MINOR, ServicePluginWire.Type.HOT_CONFIGURE_ACK,
                frame.requestId(), desktopGeneration, pluginId, pluginVersion,
                "", "", 0, 0, "application/json", json.valueToTree(result),
                true, "", ""));
    }

    private void request(ServicePluginWire.Frame frame) {
        if (!accepting.get()) {
            sendError(frame, "service_draining", "service plugin is draining", null);
            return;
        }
        IncomingRequest aggregate;
        if (frame.sequence() == 0) {
            if (incoming.size() + active.size() >= maxAdmittedRequests) {
                sendError(frame, "resource_exhausted", "service request queue is full", null);
                return;
            }
            aggregate = new IncomingRequest(frame.serviceId(), frame.operation(), frame.contentType(),
                    frame.deadlineEpochMilli(), new ByteArrayOutputStream(), 0);
            if (incoming.putIfAbsent(frame.requestId(), aggregate) != null
                    || active.containsKey(frame.requestId())) {
                sendError(frame, "duplicate_request", "request id is already active", null);
                return;
            }
        } else {
            aggregate = incoming.get(frame.requestId());
            if (aggregate == null) {
                sendError(frame, "invalid_chunk", "request chunk sequence has no start", null);
                return;
            }
        }
        ServicePluginWire.Frame complete;
        try {
            synchronized (aggregate) {
                if (frame.sequence() != aggregate.nextSequence
                        || !frame.serviceId().equals(aggregate.serviceId)
                        || !frame.operation().equals(aggregate.operation)
                        || !frame.contentType().equals(aggregate.contentType)
                        || frame.deadlineEpochMilli() != aggregate.deadlineEpochMilli) {
                    throw new IOException("request chunks are out of order or inconsistent");
                }
                byte[] chunk = payloadBytes(frame.payload());
                if (aggregate.body.size() + (long) chunk.length > ServicePluginWire.MAX_REQUEST_BYTES) {
                    throw new IOException("request exceeds aggregate payload limit");
                }
                aggregate.body.write(chunk);
                aggregate.nextSequence++;
                if (!frame.terminal()) return;
                incoming.remove(frame.requestId(), aggregate);
                byte[] body = aggregate.body.toByteArray();
                JsonNode payload = BinaryNode.valueOf(body);
                complete = new ServicePluginWire.Frame(frame.protocolMajor(), frame.protocolMinor(),
                        frame.type(), frame.requestId(), frame.desktopGeneration(), frame.pluginId(),
                        frame.pluginVersion(), frame.serviceId(), frame.operation(), 0,
                        frame.deadlineEpochMilli(), frame.contentType(), payload, true, "", "");
            }
        } catch (Exception invalid) {
            incoming.remove(frame.requestId(), aggregate);
            sendError(frame, "invalid_request_chunks", safeMessage(invalid), null);
            return;
        }
        dispatch(complete);
    }

    private void dispatch(ServicePluginWire.Frame frame) {
        RegisteredService registered = services.get(frame.serviceId());
        if (registered == null || (!registered.descriptor().operations().isEmpty()
                && !registered.descriptor().operations().contains(frame.operation()))) {
            sendError(frame, "service_not_found", "service operation is not registered", null);
            return;
        }
        ActiveRequest state = new ActiveRequest(frame, new AtomicBoolean(),
                new AtomicBoolean(), new AtomicReference<>(), new AtomicLong());
        if (active.putIfAbsent(frame.requestId(), state) != null) {
            sendError(frame, "duplicate_request", "request id is already active", null);
            return;
        }
        requests.submit(() -> {
            state.carrier().set(Thread.currentThread());
            boolean admitted = false;
            try {
                requestAdmissions.acquire();
                admitted = true;
                runningRequests.incrementAndGet();
                if (state.cancelled().get()) throw new CancellationException("request cancelled");
                if (frame.deadlineEpochMilli() > 0
                        && System.currentTimeMillis() >= frame.deadlineEpochMilli()) {
                    throw new java.util.concurrent.TimeoutException("request deadline elapsed");
                }
                registered.handler().handle(new Invocation(state));
                if (!state.terminal().get()) {
                    sendError(frame, "missing_terminal_response",
                            "service handler returned without a terminal response", state);
                }
            } catch (CancellationException cancelled) {
                new ResponseChannel(state).cancelled("application/json", new byte[0]);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                state.cancelled().set(true);
                new ResponseChannel(state).cancelled("application/json", new byte[0]);
            } catch (Throwable failure) {
                log.error("service request failed: " + frame.serviceId()
                        + "/" + frame.operation(), failure);
                sendError(frame, "service_failure", safeMessage(failure), state);
            } finally {
                if (admitted) {
                    runningRequests.decrementAndGet();
                    requestAdmissions.release();
                }
                state.carrier().set(null);
                active.remove(frame.requestId(), state);
            }
        });
    }

    private void cancel(String requestId) {
        incoming.remove(requestId);
        ActiveRequest state = active.get(requestId);
        if (state == null) return;
        state.cancelled().set(true);
        Thread carrier = state.carrier().get();
        if (carrier != null) carrier.interrupt();
    }

    private void reverseEvent(ServicePluginWire.Frame frame) throws IOException {
        ReversePending state = reversePending.get(frame.requestId());
        if (state == null || state.terminal().get()) return;
        long expected = state.nextSequence().getAndIncrement();
        if (frame.sequence() != expected || frame.terminal()) {
            throw new IOException("Desktop reverse RPC event sequence is invalid");
        }
        try {
            state.events().accept(new DesktopServiceClient.Event(
                    frame.sequence(), frame.contentType(), payloadBytes(frame.payload())));
        } catch (Throwable callbackFailure) {
            cancelReverse(state);
        }
    }

    private void reverseResponse(ServicePluginWire.Frame frame) throws IOException {
        ReversePending state = reversePending.get(frame.requestId());
        if (state == null) return;
        if (frame.sequence() != state.nextSequence().getAndIncrement()) {
            throw new IOException("Desktop reverse RPC response sequence is invalid");
        }
        byte[] chunk = payloadBytes(frame.payload());
        synchronized (state.body()) {
            if (state.body().size() + (long) chunk.length > ServicePluginWire.MAX_REQUEST_BYTES) {
                cancelReverse(state);
                throw new IOException("Desktop reverse RPC response exceeds aggregate limit");
            }
            String previousType = state.contentType().get();
            if (previousType == null) state.contentType().set(frame.contentType());
            else if (!previousType.equals(frame.contentType())) {
                throw new IOException("Desktop reverse RPC response content type changed");
            }
            ServicePluginWire.Type previousFrameType = state.responseType().get();
            if (previousFrameType == null) state.responseType().set(frame.type());
            else if (previousFrameType != frame.type()) {
                throw new IOException("Desktop reverse RPC response type changed");
            }
            state.body().write(chunk);
        }
        if (!frame.terminal()) return;
        reversePending.remove(frame.requestId(), state);
        if (!state.terminal().compareAndSet(false, true)) return;
        state.timeout().get().close();
        byte[] body;
        synchronized (state.body()) { body = state.body().toByteArray(); }
        if (frame.type() == ServicePluginWire.Type.RESPONSE) {
            state.completion().complete(new DesktopServiceClient.Response(
                    state.contentType().get(), body));
        } else {
            state.completion().completeExceptionally(new DesktopServiceException(
                    frame.errorCode(), frame.errorMessage(), body, null));
        }
    }

    private void reverseCancelled(String requestId) {
        ReversePending state = reversePending.remove(requestId);
        if (state == null || !state.terminal().compareAndSet(false, true)) return;
        state.timeout().get().close();
        state.completion().completeExceptionally(new DesktopServiceException(
                "cancelled", "Desktop cancelled the reverse RPC request", new byte[0], null));
    }

    private boolean cancelReverse(ReversePending state) {
        return failReverseLocally(state, "cancelled", "reverse RPC request cancelled");
    }

    private boolean failReverseLocally(ReversePending state, String code, String message) {
        if (!reversePending.remove(state.requestId(), state)
                || !state.terminal().compareAndSet(false, true)) return false;
        state.timeout().get().close();
        try {
            codec.write(new ServicePluginWire.Frame(ServicePluginWire.PROTOCOL_MAJOR,
                    ServicePluginWire.PROTOCOL_MINOR, ServicePluginWire.Type.CANCEL,
                    state.requestId(), desktopGeneration, pluginId, pluginVersion,
                    "", "", 0, 0, "application/json", json.nullNode(), false, "", ""));
        } catch (IOException disconnected) {
            log.debug("reverse cancellation was not delivered: " + disconnected.getMessage());
        }
        state.completion().completeExceptionally(new DesktopServiceException(
                code, message, new byte[0], null));
        return true;
    }

    private void health(ServicePluginWire.Frame frame) throws IOException {
        var memory = java.lang.management.ManagementFactory.getMemoryMXBean();
        long heapUsedMiB = memory.getHeapMemoryUsage().getUsed() / (1024 * 1024);
        long heapMaxMiB = memory.getHeapMemoryUsage().getMax() / (1024 * 1024);
        long nonHeapUsedMiB = memory.getNonHeapMemoryUsage().getUsed() / (1024 * 1024);
        Map<String, Object> payload = Map.ofEntries(
                Map.entry("state", accepting.get() ? "HEALTHY" : "STOPPING"),
                Map.entry("pid", ProcessHandle.current().pid()),
                Map.entry("activeRequests", runningRequests.get()),
                Map.entry("queuedRequests", Math.max(0, active.size() - runningRequests.get())),
                Map.entry("heapUsedMiB", heapUsedMiB),
                Map.entry("heapMaxMiB", heapMaxMiB),
                Map.entry("nonHeapUsedMiB", nonHeapUsedMiB),
                Map.entry("threadCount", java.lang.management.ManagementFactory
                        .getThreadMXBean().getThreadCount()),
                Map.entry("processCpuMillis", ProcessHandle.current().info().totalCpuDuration()
                        .map(Duration::toMillis).orElse(-1L)),
                Map.entry("services", services.keySet()),
                Map.entry("endpoints", endpoints.snapshot()));
        codec.write(new ServicePluginWire.Frame(ServicePluginWire.PROTOCOL_MAJOR,
                ServicePluginWire.PROTOCOL_MINOR, ServicePluginWire.Type.HEALTH,
                frame.requestId(), desktopGeneration, pluginId, pluginVersion,
                "", "", 0, 0, "application/json", json.valueToTree(payload),
                true, "", ""));
    }

    private void drain() {
        if (!accepting.compareAndSet(true, false)) return;
        if (endpoints != null) endpoints.stopAccepting();
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (!active.isEmpty() && System.nanoTime() < deadline) {
            try { Thread.sleep(25); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); break; }
        }
        active.values().forEach(value -> {
            value.cancelled().set(true);
            Thread carrier = value.carrier().get();
            if (carrier != null) carrier.interrupt();
        });
        incoming.clear();
    }

    private void monitorLease() {
        while (!closed.get()) {
            long elapsed = System.nanoTime() - lastHeartbeat.get();
            if (elapsed >= HEARTBEAT_WARN_NANOS) {
                accepting.set(false);
                if (endpoints != null) endpoints.stopAccepting();
            }
            if (elapsed >= HEARTBEAT_EXIT_NANOS) {
                close();
                Runtime.getRuntime().halt(75);
            }
            try { Thread.sleep(500); }
            catch (InterruptedException interrupted) { return; }
        }
    }

    private void sendCatalog() throws IOException {
        List<Map<String, Object>> catalog = services.values().stream()
                .map(value -> Map.<String, Object>of(
                        "id", value.descriptor().id(),
                        "operations", value.descriptor().operations(),
                        "streaming", value.descriptor().streaming()))
                .toList();
        codec.write(ServicePluginWire.Frame.control(ServicePluginWire.Type.SERVICE_CATALOG,
                desktopGeneration, pluginId, pluginVersion,
                json.valueToTree(Map.of("services", catalog, "endpoints", endpoints.snapshot()))));
    }

    private void sendError(ServicePluginWire.Frame request, String code, String message,
                           ActiveRequest state) {
        if (state != null && !state.terminal().compareAndSet(false, true)) return;
        try {
            codec.write(new ServicePluginWire.Frame(ServicePluginWire.PROTOCOL_MAJOR,
                    ServicePluginWire.PROTOCOL_MINOR, ServicePluginWire.Type.ERROR,
                    request.requestId(), desktopGeneration, pluginId, pluginVersion,
                    request.serviceId(), request.operation(), state == null ? 0 : state.sequence().getAndIncrement(),
                    request.deadlineEpochMilli(), "application/json", json.nullNode(), true,
                    code, message));
        } catch (IOException disconnected) {
            accepting.set(false);
        }
    }

    private URL[] classpath() throws IOException {
        // A service plugin is one self-contained, signed artifact. Loading sibling libraries would
        // let an unsigned file bypass the artifact hash and signature checked by Desktop.
        return new URL[] {pluginJar.toUri().toURL()};
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        accepting.set(false);
        Thread monitor = leaseMonitor;
        if (monitor != null && monitor != Thread.currentThread()) monitor.interrupt();
        drain();
        if (endpoints != null) endpoints.close();
        if (plugin != null) {
            var stop = Executors.newSingleThreadExecutor(RunnerExecutor.withContextClassLoader(
                    Thread.ofPlatform().name("service-plugin-stop-", 0).factory(), classLoader));
            try {
                stop.submit(() -> {
                    try { plugin.stop(); }
                    catch (Exception failure) { throw new RuntimeException(failure); }
                }).get(STOP_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            } catch (Exception failure) {
                log.warn("plugin stop did not finish cleanly: " + failure.getMessage());
            } finally {
                stop.shutdownNow();
            }
        }
        reversePending.values().forEach(state -> {
            if (state.terminal().compareAndSet(false, true)) {
                state.timeout().get().close();
                state.completion().completeExceptionally(new DesktopServiceException(
                        "desktop_disconnected", "Desktop control connection closed",
                        new byte[0], null));
            }
        });
        reversePending.clear();
        if (executor != null) executor.close();
        if (requests != null) requests.shutdownNow();
        try { codec.close(); }
        catch (IOException failure) { log.debug("control socket close failed: " + failure.getMessage()); }
        try { if (classLoader != null) classLoader.close(); }
        catch (IOException failure) { log.debug("plugin classloader close failed: " + failure.getMessage()); }
    }

    private <T> T withPluginContext(java.util.concurrent.Callable<T> action) throws Exception {
        Thread current = Thread.currentThread();
        ClassLoader previous = current.getContextClassLoader();
        current.setContextClassLoader(classLoader);
        try {
            return action.call();
        } finally {
            current.setContextClassLoader(previous);
        }
    }

    private final class Context implements ServicePluginContext {
        private final InternalServiceRegistry internal = (descriptor, handler) -> {
            RegisteredService value = new RegisteredService(descriptor, handler);
            if (services.putIfAbsent(descriptor.id(), value) != null) {
                throw new IllegalStateException("duplicate internal service: " + descriptor.id());
            }
            return () -> services.remove(descriptor.id(), value);
        };

        @Override public InternalServiceRegistry internalServices() { return internal; }
        @Override public com.javaclaw.service.api.ExternalEndpointRegistry externalEndpoints() { return endpoints; }
        @Override public DesktopServiceClient desktopServices() { return new DesktopClient(); }
        @Override public com.javaclaw.service.api.ManagedPluginExecutor executor() { return executor; }
        @Override public PluginResourceContext resources() {
            ServicePluginWire.Resources value = configure.resources();
            return new PluginResourceContext(pluginId, pluginVersion,
                    Path.of(configure.dataDirectory()).toAbsolutePath().normalize(),
                    value.heapMiB(), value.nativeMemoryMiB(), value.computeThreads(), value.ioConcurrency());
        }
        @Override public PluginNetworkContext network() {
            List<InetAddress> allowed = endpoints.configuredEndpoints().stream().map(endpoint -> {
                try { return InetAddress.getByName(endpoint.bindAddress()); }
                catch (Exception failure) { throw new IllegalArgumentException(failure); }
            }).toList();
            return new PluginNetworkContext(allowed.stream().anyMatch(address -> !address.isLoopbackAddress()), allowed);
        }
        @Override public PluginConfig config() {
            return new PluginConfig() {
                @Override public String get(String key) {
                    return pluginConfig.get().getOrDefault(key, "");
                }
                @Override public Map<String, String> asMap() { return pluginConfig.get(); }
            };
        }
        @Override public PluginLogger logger() { return new RunnerLogger(pluginId); }
    }

    private final class DesktopClient implements DesktopServiceClient {
        @Override
        public Call invoke(String serviceId, String operation, String contentType, byte[] payload,
                           Duration timeout, Consumer<DesktopServiceClient.Event> events) {
            if (closed.get() || !accepting.get()) {
                throw new DesktopServiceException("desktop_disconnected",
                        "Desktop control connection is unavailable", new byte[0], null);
            }
            if (reversePending.size() >= 32) {
                throw new DesktopServiceException("resource_exhausted",
                        "reverse RPC request queue is full", new byte[0], null);
            }
            byte[] body = payload == null ? new byte[0] : payload.clone();
            if (body.length > ServicePluginWire.MAX_REQUEST_BYTES) {
                throw new DesktopServiceException("request_too_large",
                        "reverse RPC request exceeds 32 MiB", new byte[0], null);
            }
            Duration effective = timeout == null || timeout.isZero()
                    ? Duration.ofMinutes(1) : timeout;
            if (effective.isNegative()) throw new IllegalArgumentException("timeout must not be negative");
            String requestId = "plugin:" + UUID.randomUUID();
            ReversePending state = new ReversePending(requestId,
                    events == null ? ignored -> { } : events, new CompletableFuture<>(),
                    new AtomicLong(), new AtomicBoolean(), new ByteArrayOutputStream(),
                    new AtomicReference<>(), new AtomicReference<>(), new AtomicReference<>());
            if (reversePending.putIfAbsent(requestId, state) != null) throw new AssertionError("UUID collision");
            Registration timeoutRegistration = executor.schedule(effective, () -> {
                failReverseLocally(state, "host_deadline_exceeded",
                        "reverse RPC request timed out");
            });
            state.timeout().set(timeoutRegistration);
            long deadline = System.currentTimeMillis() + effective.toMillis();
            try {
                writeReverseRequest(state, serviceId, operation,
                        contentType == null ? "application/octet-stream" : contentType, body, deadline);
            } catch (IOException failure) {
                reversePending.remove(requestId, state);
                if (state.terminal().compareAndSet(false, true)) {
                    state.timeout().get().close();
                    state.completion().completeExceptionally(new DesktopServiceException(
                            "desktop_disconnected", "cannot send reverse RPC request",
                            new byte[0], failure));
                }
            }
            return new Call() {
                @Override public String requestId() { return requestId; }
                @Override public CompletableFuture<DesktopServiceClient.Response> completion() {
                    return state.completion();
                }
                @Override public boolean cancel() { return cancelReverse(state); }
            };
        }
    }

    private void writeReverseRequest(ReversePending state, String serviceId, String operation,
                                     String contentType, byte[] body, long deadline) throws IOException {
        int count = Math.max(1, (body.length + RESPONSE_CHUNK_BYTES - 1) / RESPONSE_CHUNK_BYTES);
        for (int index = 0; index < count; index++) {
            int start = index * RESPONSE_CHUNK_BYTES;
            int end = Math.min(body.length, start + RESPONSE_CHUNK_BYTES);
            codec.write(new ServicePluginWire.Frame(ServicePluginWire.PROTOCOL_MAJOR,
                    ServicePluginWire.PROTOCOL_MINOR, ServicePluginWire.Type.REQUEST,
                    state.requestId(), desktopGeneration, pluginId, pluginVersion,
                    serviceId, operation, index, deadline, contentType,
                    BinaryNode.valueOf(java.util.Arrays.copyOfRange(body, start, end)),
                    index == count - 1, "", ""));
        }
    }

    private final class Invocation implements ServiceInvocation {
        private final ActiveRequest state;
        private final byte[] payload;

        private Invocation(ActiveRequest state) throws IOException {
            this.state = state;
            JsonNode node = state.frame().payload();
            payload = node == null || node.isNull() ? new byte[0]
                    : node.isBinary() ? node.binaryValue() : json.writeValueAsBytes(node);
            if (payload.length > ServicePluginWire.MAX_REQUEST_BYTES) {
                throw new IOException("request exceeds aggregate payload limit");
            }
        }

        @Override public String requestId() { return state.frame().requestId(); }
        @Override public String serviceId() { return state.frame().serviceId(); }
        @Override public String operation() { return state.frame().operation(); }
        @Override public String contentType() { return state.frame().contentType(); }
        @Override public byte[] payload() { return payload.clone(); }
        @Override public Instant deadline() { return state.frame().deadlineEpochMilli() <= 0
                ? Instant.MAX : Instant.ofEpochMilli(state.frame().deadlineEpochMilli()); }
        @Override public Map<String, String> metadata() { return Map.of(); }
        @Override public CancellationToken cancellation() { return state.cancelled()::get; }
        @Override public ServiceResponseChannel responses() { return new ResponseChannel(state); }
    }

    private final class ResponseChannel implements ServiceResponseChannel {
        private final ActiveRequest state;

        private ResponseChannel(ActiveRequest state) { this.state = state; }

        @Override public void event(String contentType, byte[] payload) {
            if (state.terminal().get() || state.cancelled().get()) return;
            if (payload != null && payload.length > RESPONSE_CHUNK_BYTES) {
                fail("event_too_large", "stream event exceeds the frame payload limit",
                        "application/json", new byte[0]);
                return;
            }
            send(ServicePluginWire.Type.EVENT, false, "", "", contentType, payload);
        }
        @Override public void complete(String contentType, byte[] payload) {
            if (!state.terminal().compareAndSet(false, true)) return;
            sendTerminal(ServicePluginWire.Type.RESPONSE, "", "", contentType, payload);
        }
        @Override public void fail(String errorCode, String message, String contentType, byte[] payload) {
            if (!state.terminal().compareAndSet(false, true)) return;
            sendTerminal(ServicePluginWire.Type.ERROR, errorCode, message, contentType, payload);
        }
        @Override public void cancelled(String contentType, byte[] payload) {
            if (!state.terminal().compareAndSet(false, true)) return;
            sendTerminal(ServicePluginWire.Type.ERROR, "cancelled", "request cancelled",
                    contentType, payload);
        }

        private void sendTerminal(ServicePluginWire.Type type, String code, String message,
                                  String contentType, byte[] payload) {
            byte[] value = payload == null ? new byte[0] : payload;
            if (value.length > ServicePluginWire.MAX_REQUEST_BYTES) {
                type = ServicePluginWire.Type.ERROR;
                code = "response_too_large";
                message = "service response exceeds aggregate payload limit";
                contentType = "application/json";
                value = new byte[0];
            }
            int count = Math.max(1,
                    (value.length + RESPONSE_CHUNK_BYTES - 1) / RESPONSE_CHUNK_BYTES);
            for (int index = 0; index < count; index++) {
                int start = index * RESPONSE_CHUNK_BYTES;
                int end = Math.min(value.length, start + RESPONSE_CHUNK_BYTES);
                send(type, index == count - 1, code, message, contentType,
                        java.util.Arrays.copyOfRange(value, start, end));
            }
        }

        private void send(ServicePluginWire.Type type, boolean terminal, String code,
                          String message, String contentType, byte[] payload) {
            try {
                JsonNode body = payload == null || payload.length == 0
                        ? json.nullNode() : BinaryNode.valueOf(payload);
                ServicePluginWire.Frame request = state.frame();
                codec.write(new ServicePluginWire.Frame(ServicePluginWire.PROTOCOL_MAJOR,
                        ServicePluginWire.PROTOCOL_MINOR, type, request.requestId(), desktopGeneration,
                        pluginId, pluginVersion, request.serviceId(), request.operation(),
                        state.sequence().getAndIncrement(), request.deadlineEpochMilli(),
                        contentType, body, terminal, code, message));
            } catch (IOException disconnected) {
                state.cancelled().set(true);
                Thread carrier = state.carrier().get();
                if (carrier != null) carrier.interrupt();
            }
        }
    }

    private record RegisteredService(ServiceDescriptor descriptor, ServiceRequestHandler handler) { }
    private record ActiveRequest(ServicePluginWire.Frame frame, AtomicBoolean cancelled,
                                 AtomicBoolean terminal, AtomicReference<Thread> carrier,
                                 AtomicLong sequence) { }

    private record ReversePending(
            String requestId, Consumer<DesktopServiceClient.Event> events,
            CompletableFuture<DesktopServiceClient.Response> completion,
            AtomicLong nextSequence, AtomicBoolean terminal, ByteArrayOutputStream body,
            AtomicReference<String> contentType,
            AtomicReference<ServicePluginWire.Type> responseType,
            AtomicReference<Registration> timeout) {
        private ReversePending {
            timeout.set(() -> { });
        }
    }

    private static final class IncomingRequest {
        private final String serviceId;
        private final String operation;
        private final String contentType;
        private final long deadlineEpochMilli;
        private final ByteArrayOutputStream body;
        private long nextSequence;

        private IncomingRequest(String serviceId, String operation, String contentType,
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

    private byte[] payloadBytes(JsonNode node) throws IOException {
        if (node == null || node.isNull()) return new byte[0];
        return node.isBinary() || node.isTextual()
                ? node.binaryValue() : json.writeValueAsBytes(node);
    }

    private static String safeMessage(Throwable failure) {
        String detail = null;
        java.util.Set<Throwable> visited = java.util.Collections.newSetFromMap(
                new java.util.IdentityHashMap<>());
        for (Throwable current = failure; current != null && visited.add(current);
             current = current.getCause()) {
            detail = current.getMessage() == null || current.getMessage().isBlank()
                    ? current.getClass().getSimpleName() : current.getMessage();
        }
        return detail == null ? "unknown service failure" : detail;
    }

    /**
     * Keep the SPI and Runner types process-owned while resolving every plugin implementation and
     * bundled dependency from the plugin artifact first. This prevents a plugin's Jackson, Netty or
     * inference engine version from leaking into another Runner release, without creating a second
     * copy of {@link ServicePlugin} that would make the SPI cast fail.
     */
    private static final class PluginClassLoader extends URLClassLoader {
        private static final List<String> PARENT_FIRST = List.of(
                "java.", "javax.", "jdk.", "sun.",
                "com.javaclaw.service.api.", "com.javaclaw.service.runner.");

        private PluginClassLoader(URL[] urls, ClassLoader parent) {
            super(urls, parent);
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            synchronized (getClassLoadingLock(name)) {
                Class<?> loaded = findLoadedClass(name);
                if (loaded == null) {
                    if (PARENT_FIRST.stream().anyMatch(name::startsWith)) {
                        loaded = super.loadClass(name, false);
                    } else {
                        try {
                            loaded = findClass(name);
                        } catch (ClassNotFoundException absentFromPlugin) {
                            loaded = super.loadClass(name, false);
                        }
                    }
                }
                if (resolve) resolveClass(loaded);
                return loaded;
            }
        }
    }
}
