package com.javaclaw.infrastructure.inference;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.javaclaw.application.inference.InferenceApiServerControlPort;
import com.javaclaw.application.inference.InferenceAssetIntegrityPort;
import com.javaclaw.application.inference.InferenceCatalogPort;
import com.javaclaw.application.inference.InferenceRuntimePort;
import com.javaclaw.application.plugin.v2.PluginManagementService;
import com.javaclaw.application.plugin.v2.PluginManagementService.*;
import com.javaclaw.config.WorkspaceManager;
import com.javaclaw.config.CredentialCipher;
import com.javaclaw.framework.spi.JsonSchemaValidator;
import com.javaclaw.inference.api.*;
import com.javaclaw.platform.execution.ManagedTaskExecutor;
import com.javaclaw.platform.execution.TaskScope;
import com.javaclaw.platform.execution.TaskSpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** 通用 MODEL 适配器；请求中的加载和推理固定在同一插件实例，错误不会自动重放。 */
public final class RemoteInferenceGateway implements LocalInferenceGateway, InferenceRuntimePort,
        InferenceApiServerControlPort, AutoCloseable {
    private static final Duration LOAD_TIMEOUT = Duration.ofMinutes(5);
    private static final Logger log = LoggerFactory.getLogger(RemoteInferenceGateway.class);
    private static final JsonSchemaValidator SCHEMAS = new JsonSchemaValidator();
    private final PluginManagementService plugins;
    private final WorkspaceManager workspaces;
    private final InferenceCatalogPort catalog;
    private final InferenceAssetIntegrityPort assets;
    private final ObjectMapper json;
    private final CredentialCipher credentials;
    private final ConcurrentHashMap<String, ActiveCall> requests = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<ModelKey, RuntimeProfileStatus> loaded = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<ModelKey, Object> loadLocks = new ConcurrentHashMap<>();
    private final AtomicBoolean configurationPending = new AtomicBoolean();
    private final AtomicBoolean configurationRunning = new AtomicBoolean();
    private final ConcurrentLinkedQueue<PluginStatusEvent> endpointEvents = new ConcurrentLinkedQueue<>();
    private volatile EndpointLease endpointLease;
    private volatile boolean closed;
    private AutoCloseable subscription;
    private TaskScope configurationTasks;
    private volatile String apiConfigurationError = "";

    public RemoteInferenceGateway(PluginManagementService plugins, WorkspaceManager workspaces,
                                  InferenceCatalogPort catalog, InferenceAssetIntegrityPort assets,
                                  ObjectMapper json) {
        this(plugins, workspaces, catalog, assets, json, null);
    }
    public RemoteInferenceGateway(PluginManagementService plugins, WorkspaceManager workspaces,
                                  InferenceCatalogPort catalog, InferenceAssetIntegrityPort assets,
                                  ObjectMapper json, CredentialCipher credentials) {
        this.plugins = plugins; this.workspaces = workspaces; this.catalog = catalog;
        this.assets = assets; this.json = json; this.credentials = credentials;
    }

    /** READY 后在托管任务中恢复标准配置，状态订阅回调不执行网络请求。 */
    public synchronized void init(ManagedTaskExecutor tasks) {
        if (closed) throw new IllegalStateException("模型网关已关闭");
        if (subscription != null) return;
        configurationTasks = tasks.openScope("MODEL 标准配置同步", 1);
        subscription = plugins.observeStatus(event -> {
            if (event.state() == PluginManagementService.State.READY && workspace().equals(event.workspaceId())) queueConfiguration();
            else if (Set.of(PluginManagementService.State.DRAINING, PluginManagementService.State.REVOKED,
                    PluginManagementService.State.STOPPED, PluginManagementService.State.FAILED,
                    PluginManagementService.State.QUARANTINED, PluginManagementService.State.UNINSTALLING).contains(event.state())) {
                endpointEvents.add(event); queueConfiguration();
            }
        });
        queueConfiguration();
    }
    private void queueConfiguration() {
        TaskScope scope = configurationTasks;
        if (scope == null || closed) return;
        configurationPending.set(true);
        if (!configurationRunning.compareAndSet(false, true)) return;
        try {
            scope.submit(TaskSpec.io("恢复 MODEL 标准配置"), task -> {
                try {
                    do {
                        configurationPending.set(false); task.cancellation().throwIfCancellationRequested();
                        try {
                            releaseDrainingEndpoint();
                            syncPublishedModels();
                            String provider = apiProvider();
                            if (provider != null) {
                                var state = plugins.details(workspace(), provider).summary().state();
                                if (state == PluginManagementService.State.READY || state == PluginManagementService.State.DEGRADED) {
                                    reconfigure();
                                    if (supportsInvocationLogging()) setInvocationLogging(catalog.gatewayConfiguration().invocationLoggingEnabled());
                                }
                            }
                        } catch (Exception failure) { configurationFailure(failure); }
                    } while (configurationPending.get());
                } finally {
                    configurationRunning.set(false);
                    if (configurationPending.get() && configurationTasks != null) queueConfiguration();
                }
                return null;
            });
        } catch (RuntimeException failure) { configurationRunning.set(false); configurationFailure(failure); }
    }
    private void configurationFailure(Exception failure) {
        apiConfigurationError = failure.getMessage() == null ? "MODEL 标准配置同步失败" : failure.getMessage();
        log.warn("MODEL 标准配置同步失败: {}", apiConfigurationError);
    }
    private synchronized void releaseDrainingEndpoint() {
        PluginStatusEvent event;
        while ((event = endpointEvents.poll()) != null) {
            EndpointLease current = endpointLease;
            if (current == null || !current.lease.workspaceId().equals(event.workspaceId())
                    || !current.lease.pluginId().equals(event.pluginId())) continue;
            try { disableEndpoint(current); }
            catch (Exception failure) { configurationFailure(failure); }
            finally { current.lease.close(); if (endpointLease == current) endpointLease = null; }
        }
    }

    @Override public void validateProfile(InferenceModelProfile profile) {
        validate(profile.kind(), profile.runtimeId(), profile.loadParameters(), profile.defaultParameters());
    }

    private void validate(InferenceModelProfile.Kind kind, String pluginId,
                          Map<String, Object> load, Map<String, Object> defaults) {
        var details = plugins.details(workspace(), pluginId);
        RemoteInferenceRuntimeCatalog.modelContribution(details.contributions());
        var runtime = catalog.runtime(pluginId).orElseThrow(() -> new IllegalArgumentException("推理插件尚未注册"));
        String capability = kind == InferenceModelProfile.Kind.EMBEDDING ? "embeddings" : "chat";
        if (!runtime.manifest().capabilities().contains(capability)) {
            throw new IllegalArgumentException("推理插件不支持该模型用途: " + capability);
        }
        var parameters = json.createObjectNode();
        parameters.set("load", json.valueToTree(load)); parameters.set("generation", json.valueToTree(defaults));
        var issues = SCHEMAS.validate(json.valueToTree(runtime.manifest().parameterSchema()), parameters, "/parameters");
        if (!issues.isEmpty()) throw new IllegalArgumentException("推理参数不符合插件 Schema: "
                + issues.getFirst().path() + " " + issues.getFirst().message());
    }

    @Override public InferenceChatResponse chat(InferenceChatRequest request) throws InferenceException {
        return chat(request, null);
    }
    private InferenceChatResponse chat(InferenceChatRequest request, RemoteInferenceModelSnapshots.Binding snapshots) throws InferenceException {
        var model = resolveModel(request.profileId(), InferenceModelProfile.Kind.GENERATION, snapshots);
        InferenceModelProfile profile = model.profile();
        try (RunLease lease = acquire(profile.runtimeId(), snapshots)) {
            ActiveCall call = reserve(request.requestId());
            try {
                ensureLoaded(lease, profile, call.cancelled::get, model.asset());
                return chatResponse(invokeExisting(lease, call, "chat", envelope(profile.id(), "request", chatPayload(request, false)),
                        request.timeout(), call.cancelled::get));
            } finally { requests.remove(request.requestId(), call); }
        } catch (Throwable failure) { throw inference(failure); }
    }

    @Override public StreamSession streamChat(InferenceChatRequest request, Consumer<InferenceStreamEvent> events)
            throws InferenceException {
        return streamChat(request, events, null);
    }
    private StreamSession streamChat(InferenceChatRequest request, Consumer<InferenceStreamEvent> events,
                                      RemoteInferenceModelSnapshots.Binding snapshots) throws InferenceException {
        java.util.Objects.requireNonNull(events, "events");
        var model = resolveModel(request.profileId(), InferenceModelProfile.Kind.GENERATION, snapshots);
        InferenceModelProfile profile = model.profile();
        RunLease lease = acquire(profile.runtimeId(), snapshots);
        ActiveCall call = new ActiveCall();
        if (requests.putIfAbsent(request.requestId(), call) != null) {
            lease.close(); throw new InferenceException("duplicate_request", "推理 requestId 重复", false);
        }
        CompletableFuture<InferenceChatResponse> completion = new CompletableFuture<>();
        AtomicBoolean terminal = new AtomicBoolean();
        try {
            ensureLoaded(lease, profile, call.cancelled::get, model.asset());
            events.accept(new InferenceStreamEvent(request.requestId(), InferenceStreamEvent.Type.STARTED,
                    "", List.of(), null, null, "", "", Instant.now()));
            call.attach(plugins.invoke(lease, "MODEL", contribution(lease), "chat",
                    write(envelope(profile.id(), "request", chatPayload(request, true))), request.timeout(), event -> {
                        if (terminal.get() || !"DATA".equals(event.type())) return;
                        try {
                            JsonNode payload = read(event.payloadJson());
                            String type = payload.path("type").asText();
                            if (java.util.Arrays.stream(InferenceStreamEvent.Type.values()).noneMatch(value -> value.name().equals(type))) return;
                            InferenceStreamEvent mapped = streamEvent(request.requestId(), payload);
                            if (!mapped.terminal()) events.accept(mapped);
                        } catch (Throwable failure) {
                            call.cancel();
                            finishStream(request.requestId(), null, failure, events, completion, terminal);
                        }
                    }));
            call.invocation.completion().whenComplete((result, failure) -> {
                try { finishStream(request.requestId(), result, failure, events, completion, terminal); }
                finally { requests.remove(request.requestId(), call); lease.close(); }
            });
        } catch (Throwable failure) {
            requests.remove(request.requestId(), call); call.cancel(); lease.close();
            throw inference(failure);
        }
        return new StreamSession() {
            @Override public String requestId() { return request.requestId(); }
            @Override public CompletableFuture<InferenceChatResponse> completion() { return completion; }
            @Override public boolean cancel() { return !terminal.get() && call.cancel(); }
        };
    }

    private void finishStream(String id, InvocationResult result, Throwable failure,
                              Consumer<InferenceStreamEvent> events, CompletableFuture<InferenceChatResponse> completion,
                              AtomicBoolean terminal) {
        if (!terminal.compareAndSet(false, true)) return;
        InferenceChatResponse response;
        try {
            if (failure != null) throw failure;
            response = chatResponse(read(result.payloadJson()));
        } catch (Throwable problem) {
            InferenceException mapped = inference(problem);
            try { events.accept(new InferenceStreamEvent(id, "cancelled".equalsIgnoreCase(mapped.code())
                    ? InferenceStreamEvent.Type.CANCELLED : InferenceStreamEvent.Type.ERROR, "", List.of(),
                    mapped.usage(), null, mapped.code(), mapped.getMessage(), Instant.now())); }
            finally { completion.completeExceptionally(mapped); }
            return;
        }
        try { events.accept(new InferenceStreamEvent(id, InferenceStreamEvent.Type.COMPLETE, "", response.toolCalls(),
                response.usage(), response, "", "", Instant.now())); }
        finally { completion.complete(response); }
    }

    @Override public InferenceEmbeddingResponse embeddings(InferenceEmbeddingRequest request) throws InferenceException {
        return embeddings(request, null);
    }
    private InferenceEmbeddingResponse embeddings(InferenceEmbeddingRequest request,
            RemoteInferenceModelSnapshots.Binding snapshots) throws InferenceException {
        var model = resolveModel(request.profileId(), InferenceModelProfile.Kind.EMBEDDING, snapshots);
        var profile = model.profile();
        try (RunLease lease = acquire(profile.runtimeId(), snapshots)) {
            ActiveCall call = reserve(request.requestId());
            JsonNode response;
            try {
                ensureLoaded(lease, profile, call.cancelled::get, model.asset());
                response = invokeExisting(lease, call, "embedding",
                        envelope(profile.id(), "request", Map.of("requestId", request.requestId(), "input", request.input())),
                        request.timeout(), call.cancelled::get);
            } finally { requests.remove(request.requestId(), call); }
            List<float[]> embeddings = new ArrayList<>();
            for (JsonNode vector : response.path("embeddings")) embeddings.add(json.convertValue(vector, float[].class));
            return new InferenceEmbeddingResponse(response.path("requestId").asText(request.requestId()),
                    response.path("model").asText(), response.path("dimensions").asInt(), embeddings,
                    usage(response.path("usage")), Duration.ofMillis(response.path("inferenceTimeMs").asLong()));
        } catch (Throwable failure) { throw inference(failure); }
    }

    @Override public boolean cancel(String requestId) {
        ActiveCall call = requests.get(requestId); return call != null && call.cancel();
    }
    @Override public void start(InferenceModelProfile profile) throws Exception {
        validateProfile(profile);
        try (RunLease lease = acquire(profile.runtimeId())) { ensureLoaded(lease, profile); }
    }
    @Override public void stop(UUID profileId) {
        var profile = catalog.profile(profileId);
        if (profile.isEmpty() || status(profileId).isEmpty()) return;
        try (RunLease lease = acquire(profile.get().runtimeId())) {
            invoke(lease, UUID.randomUUID().toString(), "unload", Map.of("profileId", profileId.toString()),
                    Duration.ofSeconds(30), () -> false);
            loaded.remove(key(lease, profileId));
        } catch (InferenceException failure) { throw new IllegalStateException("无法卸载插件内模型", failure); }
    }
    @Override public Optional<RuntimeProfileStatus> status(UUID profileId) {
        var profile = catalog.profile(profileId);
        if (profile.isEmpty()) return Optional.empty();
        try {
            Diagnostics diagnostics = plugins.diagnostics(workspace(), profile.get().runtimeId());
            return Optional.ofNullable(loaded.get(new ModelKey(workspace(), profile.get().runtimeId(),
                    diagnostics.instanceId(), profileId)));
        } catch (RuntimeException unavailable) { return Optional.empty(); }
    }
    @Override public ProfileProbeResult probeDraft(ProfileProbeCommand command, BooleanSupplier cancelled) throws Exception {
        BooleanSupplier token = cancelled == null ? () -> false : cancelled;
        validate(command.kind(), command.runtimeId(), command.loadParameters(), command.defaultParameters());
        var asset = requireAsset(command.assetId(), token);
        try (RunLease lease = acquire(command.runtimeId())) {
            JsonNode response = invoke(lease, command.temporaryProfileId().toString(), "probe",
                    envelope(command.temporaryProfileId(), "startup", startup(command.runtimeId(), command.name(),
                            command.kind(), asset, command.requestedContextLength(), 0,
                            command.loadParameters(), command.defaultParameters(), true)), LOAD_TIMEOUT, token);
            JsonNode model = response.has("model") ? response.path("model") : response;
            return new ProfileProbeResult(model.path("actualContextLength").asInt(),
                    model.path("actualEmbeddingDimensions").asInt(), model.path("selectedBackend").asText(),
                    stringSet(model.path("capabilities")));
        }
    }
    @Override public List<String> recentLogs(UUID profileId, int maxLines) {
        var profile = catalog.profile(profileId);
        if (profile.isEmpty()) return List.of();
        List<String> logs = plugins.diagnostics(workspace(), profile.get().runtimeId()).logs();
        return List.copyOf(logs.subList(Math.max(0, logs.size() - Math.max(0, maxLines)), logs.size()));
    }

    private void ensureLoaded(RunLease lease, InferenceModelProfile profile) throws InferenceException {
        ensureLoaded(lease, profile, () -> Thread.currentThread().isInterrupted());
    }
    private void ensureLoaded(RunLease lease, InferenceModelProfile profile, BooleanSupplier cancelled) throws InferenceException {
        ensureLoaded(lease, profile, cancelled, null);
    }
    private void ensureLoaded(RunLease lease, InferenceModelProfile profile, BooleanSupplier cancelled,
                              InferenceModelAsset frozenAsset) throws InferenceException {
        ModelKey key = key(lease, profile.id());
        Object lock = loadLocks.computeIfAbsent(key, ignored -> new Object());
        synchronized (lock) {
            if (cancelled.getAsBoolean()) throw inference(new CancellationException("模型加载已取消"));
            if (loaded.containsKey(key)) return;
            InferenceModelAsset asset = frozenAsset == null ? requireAsset(profile.assetId(), cancelled) : verifyAsset(frozenAsset, cancelled);
            JsonNode response = invoke(lease, UUID.randomUUID().toString(), "load",
                    envelope(profile.id(), "startup", startup(profile.runtimeId(), profile.name(), profile.kind(),
                            asset, profile.contextLength(), profile.embeddingDimensions(), profile.loadParameters(),
                            profile.defaultParameters(), false)), LOAD_TIMEOUT, cancelled);
            JsonNode model = response.path("model");
            loaded.put(key, new RuntimeProfileStatus(profile.id(), true, model.path("actualContextLength").asInt(),
                    model.path("actualEmbeddingDimensions").asInt(), model.path("selectedBackend").asText(),
                    stringSet(model.path("capabilities"))));
        }
    }

    private InferenceModelAsset requireAsset(UUID id, BooleanSupplier cancelled) throws InferenceException {
        var asset = catalog.asset(id).filter(value -> value.state() == InferenceModelAsset.State.READY)
                .orElseThrow(() -> new InferenceException("asset_unavailable", "模型资产尚未准备完成", false));
        return verifyAsset(asset, cancelled);
    }
    private InferenceModelAsset verifyAsset(InferenceModelAsset asset, BooleanSupplier cancelled) throws InferenceException {
        if (asset.state() != InferenceModelAsset.State.READY) throw new InferenceException("asset_unavailable", "冻结的模型资产尚未准备完成", false);
        try { assets.verifyForColdStart(asset, cancelled); return asset; }
        catch (CancellationException failure) { throw inference(failure); }
        catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw inference(failure); }
        catch (Exception failure) { throw new InferenceException("asset_integrity_failed", "模型资产完整性校验失败", false, failure); }
    }
    private InferenceModelProfile requireProfile(UUID id, InferenceModelProfile.Kind kind) throws InferenceException {
        var profile = catalog.profile(id).orElseThrow(() -> new InferenceException("profile_not_found", "模型档案不存在", false));
        return validateProfileState(profile, kind);
    }
    private InferenceModelProfile validateProfileState(InferenceModelProfile profile, InferenceModelProfile.Kind kind) throws InferenceException {
        if (profile.kind() != kind) throw new InferenceException("profile_kind_mismatch", "模型档案用途不匹配", false);
        if (profile.state() != InferenceModelProfile.State.READY && profile.state() != InferenceModelProfile.State.VERIFYING) {
            throw new InferenceException("profile_not_ready", "模型档案尚未通过探测", false);
        }
        return profile;
    }
    private RemoteInferenceModelSnapshots.Model resolveModel(UUID id, InferenceModelProfile.Kind kind,
                                                              RemoteInferenceModelSnapshots.Binding snapshots) throws InferenceException {
        if (snapshots == null) return new RemoteInferenceModelSnapshots.Model(requireProfile(id, kind), null);
        var frozen = snapshots.require(id); validateProfileState(frozen.profile(), kind); return frozen;
    }
    private RunLease acquire(String pluginId) throws InferenceException {
        return acquire(pluginId, null);
    }
    private RunLease acquire(String pluginId, RemoteInferenceModelSnapshots.Binding snapshots) throws InferenceException {
        if (snapshots != null) {
            InferenceProviderSnapshot snapshot = snapshots.providers().get(pluginId);
            if (snapshot == null) throw new InferenceException("model_snapshot_missing", "Run 未绑定此模型提供者，禁止使用当前插件实例", false);
            try { return plugins.acquireRun(snapshot.workspaceId(), snapshot.pluginId(), snapshot.artifactHash(), snapshot.configurationRevision()); }
            catch (RuntimeException failure) { throw inference(failure); }
        }
        try { return plugins.acquireRun(workspace(), pluginId); }
        catch (RuntimeException failure) { throw inference(failure); }
    }
    private JsonNode invoke(RunLease lease, String id, String operation, Object payload, Duration timeout,
                            BooleanSupplier cancelled) throws InferenceException {
        ActiveCall call = reserve(id);
        try { return invokeExisting(lease, call, operation, payload, timeout, cancelled); }
        finally { requests.remove(id, call); }
    }
    private ActiveCall reserve(String id) throws InferenceException {
        ActiveCall call = new ActiveCall();
        if (requests.putIfAbsent(id, call) != null) throw new InferenceException("duplicate_request", "推理 requestId 重复", false);
        return call;
    }
    private JsonNode invokeExisting(RunLease lease, ActiveCall call, String operation, Object payload, Duration timeout,
                                     BooleanSupplier cancelled) throws InferenceException {
        try {
            if (cancelled.getAsBoolean()) throw new CancellationException("推理请求已取消");
            call.attach(plugins.invoke(lease, "MODEL", contribution(lease), operation, write(payload), timeout, ignored -> { }));
            while (true) {
                if (cancelled.getAsBoolean()) { call.cancel(); throw new CancellationException("推理请求已取消"); }
                try { return read(call.invocation.completion().get(100, TimeUnit.MILLISECONDS).payloadJson()); }
                catch (TimeoutException pending) { /* 定期观察宿主取消信号，超时终止由插件门面负责。 */ }
            }
        } catch (InterruptedException failure) {
            call.cancel(); Thread.currentThread().interrupt(); throw inference(failure);
        } catch (Throwable failure) { throw inference(failure); }
    }

    @Override public synchronized void reconfigure() throws Exception {
        if (closed) throw new IllegalStateException("模型网关已关闭");
        var configuration = catalog.gatewayConfiguration();
        String provider = apiProvider();
        EndpointLease previous = endpointLease;
        if (!configuration.enabled()) {
            if (previous != null) {
                try { disableEndpoint(previous); }
                finally { previous.lease.close(); endpointLease = null; }
            }
            apiConfigurationError = ""; return;
        }
        if (provider == null) throw new IllegalStateException("请选择公开接口的 MODEL 插件提供者");
        RunLease candidate = acquire(provider);
        boolean retained = false;
        try {
            boolean sameInstance = previous != null && previous.lease.workspaceId().equals(candidate.workspaceId())
                    && previous.lease.instanceId().equals(candidate.instanceId());
            if (sameInstance) { candidate.close(); candidate = previous.lease; }
            else if (previous != null) {
                try { disableEndpoint(previous); }
                finally { previous.lease.close(); endpointLease = null; }
            }
            invoke(candidate, UUID.randomUUID().toString(), "gateway.configure", endpointCommand(configuration, provider, true),
                    Duration.ofSeconds(30), () -> false);
            endpointLease = new EndpointLease(candidate, configuration); retained = true; apiConfigurationError = "";
        } catch (Exception failure) { configurationFailure(failure); throw failure; }
        finally { if (!retained && (previous == null || candidate != previous.lease)) candidate.close(); }
    }
    private ObjectNode endpointCommand(InferenceCatalogPort.GatewayConfiguration configuration, String provider, boolean enabled) {
        ObjectNode payload = json.valueToTree(configuration); payload.remove("encryptedKeyStorePassword");
        if (enabled && !configuration.encryptedKeyStorePassword().isBlank()) {
            if (credentials == null) throw new IllegalStateException("TLS 配置需要宿主秘密解密服务");
            payload.put("keyStorePassword", credentials.decrypt(configuration.encryptedKeyStorePassword()));
        }
        ObjectNode command = json.createObjectNode(); command.put("enabled", enabled); command.set("configuration", payload);
        command.set("apiKeys", json.valueToTree(catalog.apiKeys().stream().filter(key -> !key.revoked()).toList()));
        command.set("catalog", enabled ? publishedCatalog(provider) : json.createObjectNode());
        return command;
    }
    private void disableEndpoint(EndpointLease current) throws InferenceException {
        invoke(current.lease, UUID.randomUUID().toString(), "gateway.configure",
                endpointCommand(current.configuration, current.lease.pluginId(), false), Duration.ofSeconds(10), () -> false);
    }
    @Override public void syncPublishedModels() throws Exception {
        for (var summary : plugins.list(workspace())) {
            if (!summary.capabilities().contains("MODEL") || (summary.state() != PluginManagementService.State.READY
                    && summary.state() != PluginManagementService.State.DEGRADED)) continue;
            try (RunLease lease = acquire(summary.id())) {
                invoke(lease, UUID.randomUUID().toString(), "models.sync", publishedCatalog(summary.id()),
                        Duration.ofSeconds(30), () -> false);
            }
        }
    }
    @Override public void setInvocationLogging(boolean enabled) throws Exception {
        String provider = apiProvider();
        if (provider == null) return;
        try (RunLease lease = acquire(provider)) {
            invoke(lease, UUID.randomUUID().toString(), "logging.configure", Map.of("enabled", enabled),
                    Duration.ofSeconds(10), () -> false);
        }
    }
    @Override public boolean supportsInvocationLogging() {
        String provider = apiProvider();
        return provider != null && catalog.runtime(provider).map(value -> value.manifest().protocol().minor() >= 2).orElse(false);
    }
    @Override public String endpoint() {
        var configuration = catalog.gatewayConfiguration();
        if (!configuration.enabled()) return "";
        String host = configuration.bindAddress();
        if (host.contains(":") && !host.startsWith("[")) host = "[" + host + "]";
        return InferenceApiServerControlPort.normalizeOpenAiBaseUrl((configuration.tlsEnabled() ? "https" : "http")
                + "://" + host + ":" + configuration.port());
    }
    @Override public ApiServerStatus status() {
        String endpoint = endpoint();
        if (endpoint.isBlank()) return new ApiServerStatus(InferenceApiServerControlPort.State.DISABLED, "", "");
        String provider = apiProvider();
        if (provider == null) return new ApiServerStatus(InferenceApiServerControlPort.State.CONFIGURED_STOPPED, endpoint, "没有可用 MODEL 插件");
        if (!apiConfigurationError.isBlank()) return new ApiServerStatus(InferenceApiServerControlPort.State.FAILED, endpoint, apiConfigurationError);
        PluginSummary summary;
        try { summary = plugins.details(workspace(), provider).summary(); }
        catch (RuntimeException failure) { return new ApiServerStatus(InferenceApiServerControlPort.State.FAILED, endpoint, failure.getMessage()); }
        if (summary.state() == PluginManagementService.State.READY || summary.state() == PluginManagementService.State.DEGRADED) {
            EndpointLease applied = endpointLease;
            boolean ready = applied != null && applied.lease.pluginId().equals(provider) && applied.lease.workspaceId().equals(workspace())
                    && applied.lease.configurationRevision() == summary.configurationRevision();
            return new ApiServerStatus(ready ? InferenceApiServerControlPort.State.AVAILABLE
                    : InferenceApiServerControlPort.State.CONFIGURED_STOPPED, endpoint, ready ? "" : "等待公开接口配置应用");
        }
        boolean failed = summary.state() == PluginManagementService.State.FAILED
                || summary.state() == PluginManagementService.State.QUARANTINED
                || summary.state() == PluginManagementService.State.REVOKED;
        return new ApiServerStatus(failed ? InferenceApiServerControlPort.State.FAILED
                : InferenceApiServerControlPort.State.CONFIGURED_STOPPED, endpoint, summary.lastError());
    }
    @Override public ServiceSnapshot serviceSnapshot() {
        String provider = apiProvider();
        if (provider == null) return new ServiceSnapshot(status(), "STOPPED", 0, 0, List.of(), false);
        try {
            var diagnostics = plugins.diagnostics(workspace(), provider);
            return new ServiceSnapshot(status(), diagnostics.state().name(), diagnostics.pid(), requests.size(),
                    diagnostics.logs(), supportsInvocationLogging());
        } catch (RuntimeException failure) {
            return new ServiceSnapshot(new ApiServerStatus(InferenceApiServerControlPort.State.FAILED, endpoint(), failure.getMessage()),
                    "FAILED", 0, requests.size(), List.of(), false);
        }
    }

    private String apiProvider() {
        String explicit = catalog.gatewayConfiguration().providerId();
        if (!explicit.isBlank()) return explicit;
        List<String> providers = plugins.list(workspace()).stream().filter(value -> value.enabled() && value.capabilities().contains("MODEL"))
                .map(PluginSummary::id).toList();
        return providers.size() == 1 ? providers.getFirst() : null;
    }
    private ObjectNode publishedCatalog(String provider) {
        ObjectNode result = json.createObjectNode();
        var profiles = result.putArray("profiles"); var aliases = result.putObject("aliases");
        Set<UUID> added = new java.util.HashSet<>();
        for (var published : catalog.publishedModels()) {
            if (!published.enabled()) continue;
            var profile = catalog.profile(published.profileId()).orElseThrow();
            if (!provider.equals(profile.runtimeId())) continue;
            if (profile.state() != InferenceModelProfile.State.READY) throw new IllegalStateException("发布的模型档案不可用");
            aliases.put(published.alias(), profile.id().toString());
            if (added.add(profile.id())) {
                var asset = catalog.asset(profile.assetId()).orElseThrow();
                profiles.add(json.valueToTree(envelope(profile.id(), "startup", startup(profile.runtimeId(), profile.name(),
                        profile.kind(), asset, profile.contextLength(), profile.embeddingDimensions(), profile.loadParameters(),
                        profile.defaultParameters(), false))));
            }
        }
        return result;
    }
    private Map<String, Object> startup(String runtimeId, String name, InferenceModelProfile.Kind kind,
                                         InferenceModelAsset asset, int context, int dimensions,
                                         Map<String, Object> load, Map<String, Object> defaults, boolean probe) {
        Path location = Path.of(asset.location()).toAbsolutePath().normalize();
        if (!Files.isDirectory(location, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(location)) {
            throw new IllegalArgumentException("模型资产目录不存在或不安全");
        }
        return Map.of("runtimeId", runtimeId, "modelPath", location.toString(), "modelKind", kind.name(),
                "modelName", name, "contextLength", context, "embeddingDimensions", dimensions,
                "loadParameters", load, "defaultParameters", defaults, "probeMode", probe);
    }
    private Map<String, Object> chatPayload(InferenceChatRequest request, boolean stream) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("requestId", request.requestId()); payload.put("messages", request.messages());
        payload.put("tools", request.tools()); payload.put("parameters", request.parameters());
        payload.put("toolChoice", request.toolChoice()); payload.put("parallelToolCalls", request.parallelToolCalls());
        payload.put("stream", stream); return payload;
    }
    private static Map<String, Object> envelope(UUID profileId, String field, Object payload) {
        return Map.of("profileId", profileId.toString(), field, payload);
    }
    private InferenceChatResponse chatResponse(JsonNode value) {
        return new InferenceChatResponse(value.path("requestId").asText(), value.path("model").asText(),
                value.path("content").asText(), value.path("reasoningContent").asText(), tools(value.path("toolCalls")),
                InferenceChatResponse.FinishReason.valueOf(value.path("finishReason").asText()), usage(value.path("usage")),
                Duration.ofMillis(value.path("queueTimeMs").asLong()), Duration.ofMillis(value.path("inferenceTimeMs").asLong()));
    }
    private InferenceStreamEvent streamEvent(String id, JsonNode value) {
        return new InferenceStreamEvent(id, InferenceStreamEvent.Type.valueOf(value.path("type").asText()),
                value.path("text").asText(), tools(value.path("toolCalls")), value.hasNonNull("usage") ? usage(value.path("usage")) : null,
                value.hasNonNull("response") ? chatResponse(value.path("response")) : null, value.path("errorCode").asText(),
                value.path("errorMessage").asText(), value.has("timestampEpochMilli")
                ? Instant.ofEpochMilli(value.path("timestampEpochMilli").asLong()) : Instant.now());
    }
    private List<InferenceToolCall> tools(JsonNode values) {
        List<InferenceToolCall> result = new ArrayList<>();
        values.forEach(value -> result.add(new InferenceToolCall(value.path("id").asText(),
                value.path("name").asText(), value.path("argumentsJson").asText()))); return List.copyOf(result);
    }
    private static InferenceUsage usage(JsonNode value) {
        return new InferenceUsage(value.path("promptTokens").asLong(), value.path("completionTokens").asLong());
    }
    private static Set<String> stringSet(JsonNode values) {
        Set<String> result = new java.util.LinkedHashSet<>(); values.forEach(value -> result.add(value.asText())); return Set.copyOf(result);
    }
    private String write(Object payload) {
        try { return json.writeValueAsString(payload); }
        catch (Exception failure) { throw new IllegalArgumentException("推理请求序列化失败", failure); }
    }
    private JsonNode read(String payload) {
        try { return json.readTree(payload); }
        catch (Exception failure) { throw new IllegalArgumentException("推理插件响应格式无效", failure); }
    }
    private static String contribution(RunLease lease) { return RemoteInferenceRuntimeCatalog.modelContribution(lease.contributions()).id(); }
    private String workspace() { return workspaces.getCurrentWorkspaceId(); }
    private static ModelKey key(RunLease lease, UUID profile) { return new ModelKey(lease.workspaceId(), lease.pluginId(), lease.instanceId(), profile); }
    private static InferenceException inference(Throwable failure) {
        Throwable value = failure;
        while ((value instanceof CompletionException || value instanceof ExecutionException) && value.getCause() != null) value = value.getCause();
        if (value instanceof InferenceException mapped) return mapped;
        if (value instanceof CancellationException || value instanceof InterruptedException) {
            return new InferenceException("cancelled", "推理请求已取消", false, value);
        }
        return new InferenceException(value instanceof PluginException plugin ? plugin.code() : "plugin_inference_error",
                value.getMessage() == null ? "模型插件调用失败" : value.getMessage(), false, value);
    }
    @Override public LocalInferenceGateway forSnapshots(List<InferenceProviderSnapshot> snapshots) {
        var immutable = RemoteInferenceModelSnapshots.read(snapshots, json);
        return new LocalInferenceGateway() {
            @Override public InferenceChatResponse chat(InferenceChatRequest request) throws InferenceException {
                return RemoteInferenceGateway.this.chat(request, immutable);
            }
            @Override public StreamSession streamChat(InferenceChatRequest request, Consumer<InferenceStreamEvent> events) throws InferenceException {
                return RemoteInferenceGateway.this.streamChat(request, events, immutable);
            }
            @Override public InferenceEmbeddingResponse embeddings(InferenceEmbeddingRequest request) throws InferenceException {
                return RemoteInferenceGateway.this.embeddings(request, immutable);
            }
            @Override public boolean cancel(String requestId) { return RemoteInferenceGateway.this.cancel(requestId); }
        };
    }
    @Override public synchronized void close() {
        closed = true;
        if (subscription != null) {
            try { subscription.close(); }
            catch (Exception failure) { log.warn("关闭 MODEL 状态订阅失败: {}", failure.getMessage()); }
            subscription = null;
        }
        if (configurationTasks != null) { configurationTasks.close(); configurationTasks = null; }
        configurationPending.set(false); endpointEvents.clear();
        EndpointLease current = endpointLease; endpointLease = null; if (current != null) current.lease.close();
        requests.values().forEach(ActiveCall::cancel); requests.clear(); loaded.clear(); loadLocks.clear();
    }
    private record EndpointLease(RunLease lease, InferenceCatalogPort.GatewayConfiguration configuration) { }
    private record ModelKey(String workspace, String plugin, String instance, UUID profile) { }
    private static final class ActiveCall {
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private volatile PluginInvocation invocation;
        void attach(PluginInvocation value) { invocation = value; if (cancelled.get()) value.cancel(); }
        boolean cancel() { boolean first = cancelled.compareAndSet(false, true); PluginInvocation current = invocation;
            return current == null ? first : current.cancel(); }
    }
}
