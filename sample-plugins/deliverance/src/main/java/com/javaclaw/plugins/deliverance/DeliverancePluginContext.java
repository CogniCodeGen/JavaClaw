package com.javaclaw.plugins.deliverance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.javaclaw.plugin.protocol.v2.CallContext;
import com.javaclaw.plugin.sdk.PluginContext;
import com.javaclaw.plugins.deliverance.internal.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/** 为插件内部引擎提供资源，不向引擎暴露宿主实现。 */
final class DeliverancePluginContext implements ServicePluginContext, AutoCloseable {
    private final PluginContext sdk;
    private final ObjectMapper json;
    private final Map<String, ServiceRequestHandler> handlers;
    private final Map<String, String> configuration = new ConcurrentHashMap<>();
    private final ScheduledExecutorService timers = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().name("deliverance-timers").daemon(true).factory());
    private final DeliveranceHttpGateway gateway;
    private volatile JsonNode gatewayConfiguration;

    DeliverancePluginContext(PluginContext sdk, ObjectMapper json,
                             Map<String, ServiceRequestHandler> handlers) throws Exception {
        this.sdk = sdk;
        this.json = json;
        this.handlers = handlers;
        JsonNode values = json.readTree(sdk.configurationJson());
        values.fields().forEachRemaining(entry -> configuration.put(entry.getKey(),
                entry.getValue().isTextual() ? entry.getValue().asText() : entry.getValue().toString()));
        gatewayConfiguration = json.createObjectNode().put("bindAddress", "127.0.0.1")
                .put("port", 18080).put("maxRequestBytes", 4 * 1024 * 1024)
                .put("requestTimeoutSeconds", 120);
        gateway = new DeliveranceHttpGateway(sdk, json, () -> gatewayConfiguration,
                () -> configuration.getOrDefault("external.apiKeyPolicies", "[]"));
    }

    void configureGateway(JsonNode request) throws Exception {
        if (request.hasNonNull("configuration")) gatewayConfiguration = request.path("configuration");
        if (request.has("apiKeys")) {
            ArrayNode policies = json.createArrayNode();
            for (JsonNode key : request.path("apiKeys")) {
                if (key.path("revoked").asBoolean(false)) continue;
                ObjectNode policy = key.deepCopy();
                policy.put("keyId", key.path("id").asText());
                policies.add(policy);
            }
            configuration.put("external.apiKeyPolicies", json.writeValueAsString(policies));
        }
        configuration.put("external.tls", Boolean.toString(gatewayConfiguration.path("tlsEnabled").asBoolean(false)));
        if (request.hasNonNull("catalog")) {
            configuration.put("inference.publishedCatalog", request.path("catalog").toString());
        }
    }

    public InternalServiceRegistry internalServices() {
        return (descriptor, handler) -> {
            if (handlers.putIfAbsent(descriptor.id(), handler) != null) {
                throw new IllegalStateException("服务重复注册");
            }
            return () -> handlers.remove(descriptor.id(), handler);
        };
    }
    public ExternalEndpointRegistry externalEndpoints() { return gateway; }
    public DesktopServiceClient desktopServices() { return this::invokeHost; }
    public ManagedPluginExecutor executor() { return new ManagedPluginExecutor() {
        public CompletionStage<Void> run(String name, Runnable task) {
            return CompletableFuture.runAsync(task, sdk.executor());
        }
        public <T> CompletionStage<T> call(String name, Callable<T> task) {
            return CompletableFuture.supplyAsync(() -> {
                try { return task.call(); } catch (Exception failure) { throw new CompletionException(failure); }
            }, sdk.executor());
        }
        public Registration scheduleAtFixedRate(String name, Duration initial, Duration interval, Runnable task) {
            ScheduledFuture<?> future = timers.scheduleAtFixedRate(() -> sdk.executor().execute(task), initial.toMillis(),
                    interval.toMillis(), TimeUnit.MILLISECONDS);
            return () -> future.cancel(true);
        }
    }; }
    public PluginResourceContext resources() {
        long heap = Runtime.getRuntime().maxMemory() / (1024 * 1024);
        return new PluginResourceContext(sdk.identity().getPluginId(), "0.0.12-8",
                sdk.privateDataDirectory(), (int) Math.min(Integer.MAX_VALUE, heap),
                positive("resources.nativeMemoryMiB", 4096),
                positive("resources.computeThreads", Math.max(1, Runtime.getRuntime().availableProcessors() - 1)),
                positive("resources.ioConcurrency", 32));
    }
    private int positive(String name, int fallback) {
        try { return Math.max(1, Integer.parseInt(configuration.getOrDefault(name, Integer.toString(fallback)))); }
        catch (NumberFormatException invalid) { return fallback; }
    }
    public PluginNetworkContext network() { return new PluginNetworkContext(false, java.util.List.of()); }
    public PluginConfig config() { return new PluginConfig() {
        public String get(String key) { return configuration.get(key); }
        public Map<String, String> asMap() { return Map.copyOf(configuration); }
    }; }
    public PluginLogger logger() { return new PluginLogger() {
        public void debug(String message) { }
        public void info(String message) { System.err.println(message); }
        public void warn(String message) { System.err.println(message); }
        public void error(String message, Throwable failure) { System.err.println(message); }
    }; }

    private DesktopServiceClient.Call invokeHost(String service, String operation, String contentType,
            byte[] payload, Duration timeout, Consumer<DesktopServiceClient.Event> events) {
        String requestId = UUID.randomUUID().toString();
        CompletableFuture<DesktopServiceClient.Response> completion = new CompletableFuture<>();
        Future<?> work = sdk.executor().submit(() -> {
            try {
                AtomicLong sequence = new AtomicLong();
                String result = sdk.host().invoke(CallContext.newBuilder().setIdentity(sdk.identity())
                                .setRequestId(requestId).setDeadlineEpochMillis(System.currentTimeMillis() + timeout.toMillis()).build(),
                        service, operation, new String(payload, StandardCharsets.UTF_8),
                        event -> events.accept(new DesktopServiceClient.Event(sequence.incrementAndGet(),
                                "application/json", event.getBytes(StandardCharsets.UTF_8))));
                completion.complete(new DesktopServiceClient.Response("application/json", result.getBytes(StandardCharsets.UTF_8)));
            } catch (Exception failure) { completion.completeExceptionally(failure); }
        });
        return new DesktopServiceClient.Call() {
            public String requestId() { return requestId; }
            public CompletableFuture<DesktopServiceClient.Response> completion() { return completion; }
            public boolean cancel() { completion.cancel(true); return work.cancel(true); }
        };
    }
    void beginDrain() { gateway.beginDrain(); timers.shutdown(); }
    @Override public void close() { gateway.close(); timers.shutdownNow(); }
}
