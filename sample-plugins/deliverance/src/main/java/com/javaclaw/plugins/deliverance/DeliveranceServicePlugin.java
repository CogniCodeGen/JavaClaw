package com.javaclaw.plugins.deliverance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.javaclaw.service.api.ExternalEndpointDescriptor;
import com.javaclaw.service.api.CancellationToken;
import com.javaclaw.service.api.Registration;
import com.javaclaw.service.api.ServiceDescriptor;
import com.javaclaw.service.api.ServiceInvocation;
import com.javaclaw.service.api.ServicePlugin;
import com.javaclaw.service.api.ServicePluginContext;

import java.time.Instant;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.Semaphore;

/** One isolated JVM that owns all loaded Deliverance generation and embedding profiles. */
public final class DeliveranceServicePlugin implements ServicePlugin {
    private final Object lifecycle = new Object();
    private final Map<String, EngineSlot> engines = new LinkedHashMap<>();
    private final Map<String, Protocol.StartupConfig> profiles = new LinkedHashMap<>();
    private final Map<String, DeliveranceServiceProtocol.ModelStatus> profileStatuses =
            new LinkedHashMap<>();
    private final Map<String, CompletableFuture<EngineSlot>> loading = new LinkedHashMap<>();
    private final Map<String, String> aliases = new LinkedHashMap<>();
    private final List<Registration> registrations = new ArrayList<>();
    private ObjectMapper json;
    private ServicePluginContext context;
    private volatile String degradedReason = "";
    private final AtomicInteger admitted = new AtomicInteger();
    private final AtomicBoolean invocationLogging = new AtomicBoolean();
    private final AtomicBoolean externalEnabled = new AtomicBoolean();
    private final Semaphore loadGate = new Semaphore(1, true);
    private volatile Registration externalRegistration;
    private static final int MAX_ADMITTED_REQUESTS = 16;

    @Override
    public void start(ServicePluginContext context) {
        this.context = context;
        this.json = new ObjectMapper();
        this.invocationLogging.set(Boolean.parseBoolean(
                context.config().get("logging.invocations")));
        String initialCatalog = context.config().get("inference.publishedCatalog");
        if (initialCatalog != null && !initialCatalog.isBlank()) {
            try {
                syncCatalog(json.readValue(initialCatalog,
                        DeliveranceServiceProtocol.CatalogSync.class));
            } catch (Exception failure) {
                throw new IllegalArgumentException("invalid published inference catalog", failure);
            }
        }
        registrations.add(context.internalServices().register(
                new ServiceDescriptor("deliverance/models", Set.of("list", "publish", "sync"), false),
                this::models));
        registrations.add(context.internalServices().register(
                new ServiceDescriptor("deliverance/load", Set.of("load"), false), this::load));
        registrations.add(context.internalServices().register(
                new ServiceDescriptor("deliverance/unload", Set.of("unload"), false), this::unload));
        registrations.add(context.internalServices().register(
                new ServiceDescriptor("deliverance/chat", Set.of("chat"), true), this::chat));
        registrations.add(context.internalServices().register(
                new ServiceDescriptor("deliverance/embedding", Set.of("embedding"), false),
                this::embedding));
        registrations.add(context.internalServices().register(
                new ServiceDescriptor("deliverance/cancel", Set.of("cancel"), false),
                invocation -> completeJson(invocation, Map.of("accepted", true))));
        registrations.add(context.internalServices().register(
                new ServiceDescriptor("deliverance/health", Set.of("health"), false), this::health));
        registrations.add(context.internalServices().register(
                new ServiceDescriptor("deliverance/logging", Set.of("status", "configure"), false),
                this::logging));
        registrations.add(context.internalServices().register(
                new ServiceDescriptor("deliverance/gateway", Set.of("status", "configure"), false),
                this::gateway));
        setExternalEnabled(Boolean.parseBoolean(context.config().get("external.enabled")));
        context.logger().info("Deliverance service plugin started");
    }

    @Override
    public void stop() {
        setExternalEnabled(false);
        List<Registration> copy = List.copyOf(registrations);
        registrations.clear();
        for (int index = copy.size() - 1; index >= 0; index--) {
            try { copy.get(index).close(); } catch (RuntimeException ignored) { }
        }
        List<EngineSlot> loaded;
        synchronized (lifecycle) {
            loaded = List.copyOf(engines.values());
            engines.clear();
            profiles.clear();
            profileStatuses.clear();
            loading.clear();
            aliases.clear();
        }
        loaded.forEach(slot -> {
            try { slot.engine().close(); } catch (RuntimeException ignored) { }
        });
    }

    private void models(ServiceInvocation invocation) throws Exception {
        if ("sync".equals(invocation.operation())) {
            DeliveranceServiceProtocol.CatalogSync request = read(
                    invocation, DeliveranceServiceProtocol.CatalogSync.class);
            syncCatalog(request);
            completeJson(invocation, Map.of("aliases", request.aliases().size(),
                    "profiles", request.profiles().size()));
            return;
        }
        if ("publish".equals(invocation.operation())) {
            DeliveranceServiceProtocol.PublishRequest request = read(
                    invocation, DeliveranceServiceProtocol.PublishRequest.class);
            synchronized (lifecycle) {
                if (request.enabled()) {
                    if (!profiles.containsKey(request.profileId())) {
                        throw new IllegalArgumentException("profile is not registered");
                    }
                    aliases.put(request.alias(), request.profileId());
                } else aliases.remove(request.alias());
            }
            completeJson(invocation, Map.of("published", request.enabled(), "alias", request.alias()));
            return;
        }
        completeJson(invocation, snapshot());
    }

    private void syncCatalog(DeliveranceServiceProtocol.CatalogSync request) {
        if (request == null) throw new IllegalArgumentException("catalog is required");
        Map<String, Protocol.StartupConfig> registrations = new LinkedHashMap<>();
        for (DeliveranceServiceProtocol.ProfileRegistration registration : request.profiles()) {
            if (registration == null || registration.profileId() == null
                    || registration.profileId().isBlank() || registration.startup() == null) {
                throw new IllegalArgumentException("catalog profile registration is invalid");
            }
            if (registrations.putIfAbsent(registration.profileId(), registration.startup()) != null) {
                throw new IllegalArgumentException("catalog contains a duplicate profile id");
            }
        }
        Map<String, String> nextAliases = new LinkedHashMap<>();
        request.aliases().forEach((alias, profileId) -> {
            if (alias == null || alias.isBlank() || profileId == null
                    || !registrations.containsKey(profileId)) {
                throw new IllegalArgumentException("catalog alias references an unknown profile");
            }
            nextAliases.put(alias, profileId);
        });
        synchronized (lifecycle) {
            registrations.forEach((profileId, startup) -> {
                EngineSlot resident = engines.get(profileId);
                if (resident != null && !resident.startup().equals(startup)) {
                    throw new IllegalStateException(
                            "resident profile cannot be replaced with different parameters");
                }
                if (loading.containsKey(profileId)
                        && !startup.equals(profiles.get(profileId))) {
                    throw new IllegalStateException("loading profile cannot be replaced");
                }
            });
            profiles.putAll(registrations);
            aliases.clear();
            aliases.putAll(nextAliases);
        }
    }

    private void load(ServiceInvocation invocation) throws Exception {
        DeliveranceServiceProtocol.LoadRequest request = read(
                invocation, DeliveranceServiceProtocol.LoadRequest.class);
        if (request.profileId() == null || request.profileId().isBlank() || request.startup() == null) {
            throw new IllegalArgumentException("profileId and startup are required");
        }
        invocation.cancellation().throwIfCancellationRequested();
        synchronized (lifecycle) {
            Protocol.StartupConfig previous = profiles.putIfAbsent(
                    request.profileId(), request.startup());
            if (previous != null && !previous.equals(request.startup())) {
                throw new IllegalStateException("profile id is already registered with different parameters");
            }
        }
        try {
            EngineSlot slot = ensureEngine(request.profileId(), invocation.cancellation());
            completeJson(invocation, new DeliveranceServiceProtocol.LoadResponse(status(slot)));
        } catch (Throwable failure) {
            synchronized (lifecycle) {
                if (!engines.containsKey(request.profileId())) {
                    profiles.remove(request.profileId(), request.startup());
                    profileStatuses.remove(request.profileId());
                }
            }
            throw failure;
        }
    }

    private long estimateResourceBytes(
            Protocol.StartupConfig startup, CancellationToken cancellation) throws Exception {
        Path model = Path.of(startup.modelPath()).toAbsolutePath().normalize();
        if (Files.isSymbolicLink(model) || !Files.isDirectory(model, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("model path is not a managed directory");
        }
        long modelBytes = 0;
        try (var paths = Files.walk(model)) {
            for (Path path : paths.filter(value -> Files.isRegularFile(
                    value, LinkOption.NOFOLLOW_LINKS)).toList()) {
                requireActive(cancellation);
                modelBytes = Math.addExact(modelBytes, Files.size(path));
            }
        }
        long configuredMiB = Math.addExact((long) context.resources().heapMiB(),
                context.resources().nativeMemoryMiB());
        long configuredBytes = Math.multiplyExact(configuredMiB, 1024L * 1024L);
        long overhead = Math.max(256L * 1024 * 1024, modelBytes / 10);
        long kvEntries = number(startup.loadParameters().get("kvCacheMaxEntries"), 10_000);
        long kvEstimate = Math.multiplyExact(Math.max(0, kvEntries), 16L * 1024L);
        long required = Math.addExact(Math.addExact(modelBytes, overhead), kvEstimate);
        if (required > configuredBytes) {
            throw new IllegalStateException("resource_exhausted: model requires approximately "
                    + ((required + 1024 * 1024 - 1) / (1024 * 1024))
                    + " MiB but this service process reserves " + configuredMiB + " MiB");
        }
        return required;
    }

    private void unload(ServiceInvocation invocation) throws Exception {
        DeliveranceServiceProtocol.UnloadRequest request = read(
                invocation, DeliveranceServiceProtocol.UnloadRequest.class);
        EngineSlot removed;
        synchronized (lifecycle) {
            if (loading.containsKey(request.profileId())) {
                throw new IllegalStateException("model is still loading");
            }
            removed = engines.get(request.profileId());
            if (removed != null && removed.active().get() != 0) {
                throw new IllegalStateException("model still has active requests");
            }
            if (removed != null) engines.remove(request.profileId(), removed);
            profiles.remove(request.profileId());
            profileStatuses.remove(request.profileId());
            aliases.values().removeIf(value -> value.equals(request.profileId()));
            lifecycle.notifyAll();
        }
        if (removed != null) removed.engine().close();
        completeJson(invocation, Map.of("unloaded", removed != null));
    }

    private void chat(ServiceInvocation invocation) throws Exception {
        DeliveranceServiceProtocol.ChatRequest envelope = read(
                invocation, DeliveranceServiceProtocol.ChatRequest.class);
        long started = System.nanoTime();
        Protocol.Usage usage = new Protocol.Usage(0, 0);
        String status = "error";
        EngineSlot slot = null;
        try {
            slot = acquire(envelope.profileId(), "GENERATION", invocation.cancellation());
            Protocol.ChatResponse response = slot.engine().chat(
                    envelope.request(), invocation.cancellation(), event -> {
                invocation.cancellation().throwIfCancellationRequested();
                if (!Thread.currentThread().isInterrupted()) eventJson(invocation, event);
            }, 0);
            invocation.cancellation().throwIfCancellationRequested();
            usage = response.usage();
            status = "success";
            completeJson(invocation, response);
        } catch (DeliveranceEngine.CountedInferenceFailure failure) {
            usage = failure.usage();
            status = failure.cancelled() ? "cancelled" : "error";
            failJson(invocation, failure.cancelled() ? "cancelled" : "inference_error",
                    failure.getMessage(), new DeliveranceServiceProtocol.ErrorUsage(failure.usage()));
        } catch (CancellationException cancelledFailure) {
            status = "cancelled";
            failJson(invocation, "cancelled", "inference request cancelled",
                    new DeliveranceServiceProtocol.ErrorUsage(new Protocol.Usage(0, 0)));
        } finally {
            if (slot != null) release(slot);
            logInvocation("internal", "deliverance/chat", "chat", envelope.profileId(),
                    envelope.request() == null ? invocation.requestId() : envelope.request().requestId(),
                    status, started, usage);
        }
    }

    private void embedding(ServiceInvocation invocation) throws Exception {
        DeliveranceServiceProtocol.EmbeddingRequest envelope = read(
                invocation, DeliveranceServiceProtocol.EmbeddingRequest.class);
        long started = System.nanoTime();
        Protocol.Usage usage = new Protocol.Usage(0, 0);
        String status = "error";
        EngineSlot slot = null;
        try {
            slot = acquire(envelope.profileId(), "EMBEDDING", invocation.cancellation());
            Protocol.EmbeddingResponse response = slot.engine().embeddings(
                    envelope.request(), invocation.cancellation());
            invocation.cancellation().throwIfCancellationRequested();
            usage = response.usage();
            status = "success";
            completeJson(invocation, response);
        } catch (DeliveranceEngine.CountedInferenceFailure failure) {
            usage = failure.usage();
            status = failure.cancelled() ? "cancelled" : "error";
            failJson(invocation, failure.cancelled() ? "cancelled" : "inference_error",
                    failure.getMessage(), new DeliveranceServiceProtocol.ErrorUsage(failure.usage()));
        } catch (CancellationException cancelledFailure) {
            status = "cancelled";
            failJson(invocation, "cancelled", "inference request cancelled",
                    new DeliveranceServiceProtocol.ErrorUsage(usage));
        } finally {
            if (slot != null) release(slot);
            logInvocation("internal", "deliverance/embedding", "embedding", envelope.profileId(),
                    envelope.request() == null ? invocation.requestId() : envelope.request().requestId(),
                    status, started, usage);
        }
    }

    private void logging(ServiceInvocation invocation) throws Exception {
        if ("configure".equals(invocation.operation())) {
            DeliveranceServiceProtocol.LoggingConfigure request = read(
                    invocation, DeliveranceServiceProtocol.LoggingConfigure.class);
            invocationLogging.set(request.enabled());
        }
        completeJson(invocation, new DeliveranceServiceProtocol.LoggingStatus(
                invocationLogging.get()));
    }

    private void gateway(ServiceInvocation invocation) throws Exception {
        if ("configure".equals(invocation.operation())) {
            DeliveranceServiceProtocol.GatewayConfigure request = read(
                    invocation, DeliveranceServiceProtocol.GatewayConfigure.class);
            setExternalEnabled(request.enabled());
        }
        completeJson(invocation, new DeliveranceServiceProtocol.GatewayStatus(
                externalEnabled.get()));
    }

    private synchronized void setExternalEnabled(boolean enabled) {
        if (enabled == externalEnabled.get() && (enabled == (externalRegistration != null))) return;
        if (!enabled) {
            Registration registration = externalRegistration;
            externalRegistration = null;
            externalEnabled.set(false);
            if (registration != null) {
                try { registration.close(); } catch (RuntimeException ignored) { }
            }
            return;
        }
        if (context == null) throw new IllegalStateException("plugin context is unavailable");
        Registration registration = context.externalEndpoints().register(
                new ExternalEndpointDescriptor("openai",
                        Boolean.parseBoolean(context.config().get("external.tls"))
                                ? ExternalEndpointDescriptor.Protocol.HTTPS
                                : ExternalEndpointDescriptor.Protocol.HTTP,
                        "/", Set.of("models", "chat", "embeddings", "sse")),
                new DeliveranceOpenAiEndpoint(this, json, context.config(),
                        context.desktopServices(), context.logger()));
        externalRegistration = registration;
        externalEnabled.set(true);
    }

    void logInvocation(String source, String route, String operation, String model,
                       String requestId, String status, long startedNanos, Protocol.Usage usage) {
        if (!invocationLogging.get() || context == null) return;
        Protocol.Usage safeUsage = usage == null ? new Protocol.Usage(0, 0) : usage;
        long durationMs = Math.max(0, TimeUnit.NANOSECONDS.toMillis(
                System.nanoTime() - startedNanos));
        context.logger().info("[INFERENCE_CALL] time=" + Instant.now()
                + " source=" + logField(source)
                + " route=" + logField(route)
                + " operation=" + logField(operation)
                + " model=" + logField(model)
                + " requestId=" + logField(requestId)
                + " status=" + logField(status)
                + " durationMs=" + durationMs
                + " promptTokens=" + Math.max(0, safeUsage.promptTokens())
                + " completionTokens=" + Math.max(0, safeUsage.completionTokens()));
    }

    private static String logField(String value) {
        if (value == null || value.isBlank()) return "-";
        String sanitized = value.replaceAll("[\\r\\n\\t ]+", "_")
                .replaceAll("[^A-Za-z0-9._:/@-]", "?");
        return sanitized.substring(0, Math.min(192, sanitized.length()));
    }

    private void health(ServiceInvocation invocation) throws Exception {
        completeJson(invocation, Map.of("engine", "deliverance", "version", "0.0.12",
                "models", snapshot(), "processId", ProcessHandle.current().pid(),
                "state", degradedReason.isBlank() ? "HEALTHY" : "DEGRADED",
                "detail", degradedReason));
    }

    private EngineSlot acquire(
            String profileId, String expectedKind, CancellationToken cancellation) {
        if (admitted.incrementAndGet() > MAX_ADMITTED_REQUESTS) {
            admitted.decrementAndGet();
            throw new IllegalStateException("queue_full");
        }
        EngineSlot value;
        try {
            while (true) {
                value = ensureEngine(profileId, cancellation);
                synchronized (lifecycle) {
                    if (engines.get(profileId) != value) continue;
                    if (!value.startup().modelKind().equalsIgnoreCase(expectedKind)) {
                        throw new IllegalArgumentException("profile kind mismatch");
                    }
                    value.active().incrementAndGet();
                    break;
                }
            }
        } catch (RuntimeException failure) {
            admitted.decrementAndGet();
            throw failure;
        } catch (Exception failure) {
            admitted.decrementAndGet();
            throw new IllegalStateException("model could not be loaded", failure);
        }
        try {
            value.gate().acquire();
            return value;
        } catch (InterruptedException interrupted) {
            value.active().decrementAndGet();
            admitted.decrementAndGet();
            Thread.currentThread().interrupt();
            throw new CancellationException("inference request cancelled");
        }
    }

    private void release(EngineSlot slot) {
        slot.gate().release();
        slot.active().decrementAndGet();
        admitted.decrementAndGet();
        slot.lastUsed = Instant.now();
        synchronized (lifecycle) { lifecycle.notifyAll(); }
    }

    List<DeliveranceServiceProtocol.ModelStatus> snapshot() {
        synchronized (lifecycle) {
            return profiles.keySet().stream().map(profileId -> {
                EngineSlot resident = engines.get(profileId);
                if (resident != null) return status(resident);
                DeliveranceServiceProtocol.ModelStatus known = profileStatuses.get(profileId);
                if (known != null) return new DeliveranceServiceProtocol.ModelStatus(
                        known.profileId(), known.name(), known.kind(), false,
                        known.actualContextLength(), known.actualEmbeddingDimensions(),
                        known.selectedBackend(), known.capabilities());
                Protocol.StartupConfig startup = profiles.get(profileId);
                return new DeliveranceServiceProtocol.ModelStatus(profileId, startup.modelName(),
                        startup.modelKind(), false, 0, 0, "", Set.of());
            }).toList();
        }
    }

    Map<String, DeliveranceServiceProtocol.ModelStatus> publishedSnapshot() {
        synchronized (lifecycle) {
            Map<String, DeliveranceServiceProtocol.ModelStatus> result = new LinkedHashMap<>();
            aliases.forEach((alias, profileId) -> snapshot().stream()
                    .filter(value -> value.profileId().equals(profileId)).findFirst()
                    .ifPresent(value -> result.put(alias, value)));
            return Map.copyOf(result);
        }
    }

    String resolveAlias(String alias) {
        synchronized (lifecycle) { return aliases.get(alias); }
    }

    EngineSlot externalAcquire(
            String profileId, String expectedKind, CancellationToken cancellation) {
        return acquire(profileId, expectedKind, cancellation);
    }
    void externalRelease(EngineSlot slot) { release(slot); }

    private DeliveranceServiceProtocol.ModelStatus status(EngineSlot slot) {
        DeliveranceServiceProtocol.ModelStatus value = new DeliveranceServiceProtocol.ModelStatus(
                slot.profileId(), slot.startup().modelName(),
                slot.startup().modelKind(), true, slot.engine().actualContextLength(),
                slot.engine().actualEmbeddingDimensions(), slot.engine().selectedBackend(),
                slot.engine().capabilities());
        synchronized (lifecycle) { profileStatuses.put(slot.profileId(), value); }
        return value;
    }

    private EngineSlot ensureEngine(String profileId, CancellationToken cancellation) throws Exception {
        CompletableFuture<EngineSlot> future;
        boolean owner = false;
        Protocol.StartupConfig startup;
        synchronized (lifecycle) {
            EngineSlot resident = engines.get(profileId);
            if (resident != null) return resident;
            startup = profiles.get(profileId);
            if (startup == null) throw new IllegalArgumentException("profile is not registered");
            future = loading.get(profileId);
            if (future == null) {
                future = new CompletableFuture<>();
                loading.put(profileId, future);
                owner = true;
            }
        }
        if (!owner) {
            return awaitLoading(future, cancellation);
        }
        DeliveranceEngine created = null;
        boolean acquired = false;
        List<EngineSlot> memoryEvicted = List.of();
        try {
            acquireLoadGate(cancellation);
            acquired = true;
            synchronized (lifecycle) {
                EngineSlot resident = engines.get(profileId);
                if (resident != null) {
                    loading.remove(profileId);
                    future.complete(resident);
                    return resident;
                }
            }
            requireActive(cancellation);
            long reservedBytes = estimateResourceBytes(startup, cancellation);
            memoryEvicted = reserveMemory(reservedBytes, profileId, cancellation);
            closeSlots(memoryEvicted, "reclaimed model did not close cleanly");
            requireActive(cancellation);
            created = new DeliveranceEngine(startup, json);
            requireActive(cancellation);
            EngineSlot slot = new EngineSlot(profileId, startup, created,
                    new AtomicInteger(), Instant.now(), reservedBytes);
            installResident(slot, cancellation);
            created = null; // ownership moved into the resident slot
            status(slot);
            degradedReason = "";
            return slot;
        } catch (Throwable failure) {
            if (created != null) created.close();
            if (!memoryEvicted.isEmpty()) {
                restoreEvicted(memoryEvicted, failure);
            }
            synchronized (lifecycle) {
                loading.remove(profileId);
                future.completeExceptionally(failure);
            }
            if (failure instanceof Exception exception) throw exception;
            if (failure instanceof Error error) throw error;
            throw new IllegalStateException(failure);
        } finally {
            if (acquired) loadGate.release();
        }
    }

    private void closeSlots(List<EngineSlot> slots, String warning) {
        for (EngineSlot slot : slots) {
            try { slot.engine().close(); }
            catch (RuntimeException failure) {
                context.logger().warn(warning + ": " + slot.profileId()
                        + ": " + safeMessage(failure));
            }
        }
    }

    private void restoreEvicted(List<EngineSlot> evicted, Throwable original) {
        boolean interrupted = Thread.interrupted();
        List<String> failures = new ArrayList<>();
        try {
            for (EngineSlot previous : evicted) {
                DeliveranceEngine restored = null;
                try {
                    synchronized (lifecycle) {
                        if (engines.containsKey(previous.profileId())
                                || !previous.startup().equals(profiles.get(previous.profileId()))) {
                            continue;
                        }
                    }
                    restored = new DeliveranceEngine(previous.startup(), json);
                    EngineSlot slot = new EngineSlot(previous.profileId(), previous.startup(), restored,
                            new AtomicInteger(), Instant.now(), previous.reservedBytes());
                    synchronized (lifecycle) {
                        if (engines.putIfAbsent(previous.profileId(), slot) == null) {
                            status(slot);
                            restored = null;
                            lifecycle.notifyAll();
                        }
                    }
                } catch (Throwable restoreFailure) {
                    original.addSuppressed(restoreFailure);
                    failures.add(previous.profileId() + ": " + safeMessage(restoreFailure));
                } finally {
                    if (restored != null) restored.close();
                }
            }
        } finally {
            if (interrupted) Thread.currentThread().interrupt();
        }
        if (failures.isEmpty()) {
            degradedReason = "";
            context.logger().info("new model load failed; previous resident models were restored");
        } else {
            degradedReason = "failed to restore previous model: " + String.join("; ", failures);
            context.logger().error(degradedReason, original);
        }
    }

    private EngineSlot awaitLoading(
            CompletableFuture<EngineSlot> future, CancellationToken cancellation) throws Exception {
        while (true) {
            requireActive(cancellation);
            try {
                return future.get(100, TimeUnit.MILLISECONDS);
            } catch (TimeoutException waiting) {
                // Polling keeps a queued waiter responsive to its own cancellation token.
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new CancellationException("model load wait cancelled");
            } catch (ExecutionException failure) {
                Throwable cause = failure.getCause();
                if (cause instanceof Exception exception) throw exception;
                if (cause instanceof Error error) throw error;
                throw new IllegalStateException(cause);
            }
        }
    }

    private void acquireLoadGate(CancellationToken cancellation) {
        while (true) {
            requireActive(cancellation);
            try {
                if (loadGate.tryAcquire(100, TimeUnit.MILLISECONDS)) return;
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new CancellationException("model load admission cancelled");
            }
        }
    }

    private List<EngineSlot> reserveMemory(
            long requestedBytes, String profileId, CancellationToken cancellation) {
        long budgetBytes = Math.multiplyExact(Math.addExact(
                (long) context.resources().heapMiB(), context.resources().nativeMemoryMiB()),
                1024L * 1024L);
        while (true) {
            requireActive(cancellation);
            synchronized (lifecycle) {
                long residentBytes = engines.values().stream()
                        .mapToLong(EngineSlot::reservedBytes).sum();
                if (residentBytes + requestedBytes <= budgetBytes) return List.of();
                List<EngineSlot> idle = engines.values().stream()
                        .filter(value -> !value.profileId().equals(profileId))
                        .filter(value -> value.active().get() == 0)
                        .sorted(Comparator.comparing(EngineSlot::lastUsed)).toList();
                List<EngineSlot> victims = new ArrayList<>();
                long remaining = residentBytes;
                for (EngineSlot candidate : idle) {
                    victims.add(candidate);
                    remaining -= candidate.reservedBytes();
                    if (remaining + requestedBytes <= budgetBytes) break;
                }
                if (remaining + requestedBytes <= budgetBytes) {
                    victims.forEach(value -> engines.remove(value.profileId(), value));
                    return List.copyOf(victims);
                }
                waitForResidentChange(cancellation);
            }
        }
    }

    private void installResident(EngineSlot slot, CancellationToken cancellation) {
        requireActive(cancellation);
        synchronized (lifecycle) {
            EngineSlot previous = engines.putIfAbsent(slot.profileId(), slot);
            if (previous != null) {
                throw new IllegalStateException("model became resident while loading");
            }
            CompletableFuture<EngineSlot> future = loading.remove(slot.profileId());
            if (future != null) future.complete(slot);
            lifecycle.notifyAll();
        }
    }

    private void waitForResidentChange(CancellationToken cancellation) {
        try {
            lifecycle.wait(100);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new CancellationException("model residency wait cancelled");
        }
        requireActive(cancellation);
    }

    private static void requireActive(CancellationToken cancellation) {
        if (Thread.currentThread().isInterrupted()
                || cancellation != null && cancellation.isCancellationRequested()) {
            throw new CancellationException("model operation cancelled");
        }
    }

    private static long number(Object value, long fallback) {
        if (value == null) return fallback;
        if (value instanceof Number number) return number.longValue();
        try { return Long.parseLong(String.valueOf(value)); }
        catch (NumberFormatException failure) {
            throw new IllegalArgumentException("resource parameter must be an integer", failure);
        }
    }

    private <T> T read(ServiceInvocation invocation, Class<T> type) throws Exception {
        invocation.cancellation().throwIfCancellationRequested();
        return json.readValue(invocation.payload(), type);
    }

    private void completeJson(ServiceInvocation invocation, Object value) throws Exception {
        invocation.responses().complete("application/json", json.writeValueAsBytes(value));
    }

    private void eventJson(ServiceInvocation invocation, Object value) {
        try { invocation.responses().event("application/json", json.writeValueAsBytes(value)); }
        catch (Exception failure) { throw new IllegalStateException(failure); }
    }

    private void failJson(ServiceInvocation invocation, String code, String message, Object value) {
        try {
            invocation.responses().fail(code, message == null ? code : message,
                    "application/json", json.writeValueAsBytes(value));
        } catch (Exception failure) { throw new IllegalStateException(failure); }
    }

    private static String safeMessage(Throwable failure) {
        return failure.getMessage() == null
                ? failure.getClass().getSimpleName() : failure.getMessage();
    }

    static final class EngineSlot {
        private final String profileId;
        private final Protocol.StartupConfig startup;
        private final DeliveranceEngine engine;
        private final AtomicInteger active;
        private final long reservedBytes;
        private final Semaphore gate = new Semaphore(1, true);
        private volatile Instant lastUsed;

        private EngineSlot(String profileId, Protocol.StartupConfig startup,
                           DeliveranceEngine engine, AtomicInteger active, Instant lastUsed,
                           long reservedBytes) {
            this.profileId = profileId;
            this.startup = startup;
            this.engine = engine;
            this.active = active;
            this.lastUsed = lastUsed;
            this.reservedBytes = reservedBytes;
        }

        String profileId() { return profileId; }
        Protocol.StartupConfig startup() { return startup; }
        DeliveranceEngine engine() { return engine; }
        AtomicInteger active() { return active; }
        Semaphore gate() { return gate; }
        Instant lastUsed() { return lastUsed; }
        long reservedBytes() { return reservedBytes; }
    }
}
