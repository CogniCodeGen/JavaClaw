package com.javaclaw.infrastructure.inference.serviceplugin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.javaclaw.application.inference.InferenceApiServerControlPort;
import com.javaclaw.application.inference.InferenceAssetIntegrityPort;
import com.javaclaw.application.inference.InferenceCatalogPort;
import com.javaclaw.application.inference.InferenceRuntimePort;
import com.javaclaw.application.serviceplugin.ServicePluginInvocationPort;
import com.javaclaw.application.serviceplugin.ServicePluginManagementApplicationService;
import com.javaclaw.config.CredentialCipher;
import com.javaclaw.inference.api.InferenceChatRequest;
import com.javaclaw.inference.api.InferenceChatResponse;
import com.javaclaw.inference.api.InferenceEmbeddingRequest;
import com.javaclaw.inference.api.InferenceEmbeddingResponse;
import com.javaclaw.inference.api.InferenceMessage;
import com.javaclaw.inference.api.InferenceModelAsset;
import com.javaclaw.inference.api.InferenceModelProfile;
import com.javaclaw.inference.api.InferenceRequestPriority;
import com.javaclaw.inference.api.InferenceStreamEvent;
import com.javaclaw.inference.api.InferenceToolCall;
import com.javaclaw.inference.api.InferenceUsage;
import com.javaclaw.inference.api.LocalInferenceGateway;
import com.javaclaw.infrastructure.serviceplugin.ServicePluginDefinition;
import com.javaclaw.infrastructure.serviceplugin.ServicePluginProcessManager;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Host adapter for the built-in Deliverance SERVICE_PLUGIN. */
public final class DeliveranceServicePluginGateway implements
        LocalInferenceGateway, InferenceApiServerControlPort {
    public static final String PLUGIN_ID = "builtin-deliverance";
    private static final Duration START_TIMEOUT = Duration.ofMinutes(2);
    private static final Set<String> REQUIRED_CAPABILITIES = Set.of(
            "chat", "streaming", "reasoning", "tools", "exact_usage",
            "terminal_usage", "embeddings", "cancellation");

    private final InferenceCatalogPort catalog;
    private final InferenceAssetIntegrityPort assets;
    private final ServicePluginProcessManager processes;
    private final ObjectMapper json;
    private final CredentialCipher credentials;
    private final ConcurrentHashMap<UUID, InferenceRuntimePort.RuntimeProfileStatus> loaded =
            new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ServicePluginInvocationPort.Invocation> requests =
            new ConcurrentHashMap<>();
    private volatile InferenceCatalogPort.RuntimeInstallation activeRuntime;
    private volatile long loadedProcessPid;

    public DeliveranceServicePluginGateway(
            InferenceCatalogPort catalog,
            InferenceAssetIntegrityPort assets,
            ServicePluginProcessManager processes,
            ObjectMapper json,
            CredentialCipher credentials) {
        this.catalog = catalog;
        this.assets = assets;
        this.processes = processes;
        this.json = json;
        this.credentials = credentials;
    }

    /** Called only after the service-plugin scanner has verified the signed JAR and descriptor. */
    public synchronized void registerRuntime(InferenceCatalogPort.RuntimeInstallation runtime) {
        ServicePluginDefinition candidate = definition(runtime);
        InferenceCatalogPort.RuntimeInstallation previousRuntime = activeRuntime;
        Optional<ServicePluginDefinition> previousDefinition = processes.definition(PLUGIN_ID);
        boolean changedRuntime = activeRuntime != null
                && !activeRuntime.manifest().runtimeId().equals(runtime.manifest().runtimeId());
        boolean restart = changedRuntime && serviceHealthy();
        try {
            if (restart) processes.stop(PLUGIN_ID);
            processes.register(candidate);
            activeRuntime = runtime;
            if (changedRuntime) resetLoadedState();
            if (restart) {
                processes.start(PLUGIN_ID);
                restorePublishedProfiles();
            }
        } catch (RuntimeException failure) {
            rollbackRuntimeSwitch(previousRuntime, previousDefinition, restart, failure);
            throw failure;
        }
    }

    private ServicePluginDefinition definition(InferenceCatalogPort.RuntimeInstallation runtime) {
        ServicePluginDefinition base = processes.definition(PLUGIN_ID).orElseThrow(() ->
                new IllegalStateException("Deliverance 服务插件尚未由插件管理器注册"));
        String hash = runtime.manifest().files().stream()
                .filter(file -> file.path().equals("deliverance.jar"))
                .findFirst().orElseThrow(() -> new IllegalArgumentException(
                        "Deliverance 运行时清单缺少服务插件 JAR")).sha256();
        if (!hash.equalsIgnoreCase(base.artifactSha256())) {
            throw new SecurityException("推理目录与服务插件工件哈希不一致");
        }
        var gateway = catalog.gatewayConfiguration();
        List<InferenceCatalogPort.ApiKeyRecord> keys = catalog.apiKeys().stream()
                .filter(key -> !key.revoked()).toList();
        boolean external = gateway.enabled() && !keys.isEmpty();
        String verifier = keys.stream().map(key -> "pbkdf2$" + key.prefix() + "$"
                        + key.salt() + "$" + key.digest())
                .collect(java.util.stream.Collectors.joining(";"));
        List<ServicePluginManagementApplicationService.EndpointConfiguration> endpoints = !keys.isEmpty()
                ? List.of(new ServicePluginManagementApplicationService.EndpointConfiguration(
                "openai", gateway.tlsEnabled()
                        ? ServicePluginManagementApplicationService.Protocol.HTTPS
                        : ServicePluginManagementApplicationService.Protocol.HTTP,
                gateway.bindAddress(), gateway.port(), gateway.tlsEnabled(),
                gateway.allowInsecureLanWithoutTls(), gateway.keyStorePath().isBlank()
                        ? null : Path.of(gateway.keyStorePath()),
                gateway.encryptedKeyStorePassword().isBlank() ? ""
                        : credentials.decrypt(gateway.encryptedKeyStorePassword()),
                verifier,
                keys.stream().mapToInt(InferenceCatalogPort.ApiKeyRecord::requestsPerMinute).sum(),
                keys.stream().mapToLong(InferenceCatalogPort.ApiKeyRecord::tokensPerMinute).sum(),
                Math.max(1, keys.stream().mapToInt(
                        InferenceCatalogPort.ApiKeyRecord::maxConcurrent).sum()),
                64, gateway.maxRequestBytes(), gateway.requestTimeoutSeconds())) : List.of();
        Map<String, String> config = new java.util.LinkedHashMap<>(base.config());
        config.put("external.enabled", Boolean.toString(external));
        config.put("external.tls", Boolean.toString(gateway.tlsEnabled()));
        config.put("external.apiKeyPolicies", apiKeyPolicies(keys));
        config.put("logging.invocations",
                Boolean.toString(gateway.invocationLoggingEnabled()));
        try {
            config.put("inference.publishedCatalog",
                    json.writeValueAsString(publishedCatalog()));
        } catch (Exception failure) {
            throw new IllegalStateException("无法生成 Deliverance 模型目录", failure);
        }
        return new ServicePluginDefinition(base.id(), base.name(), base.version(), base.apiVersion(),
                base.mainClass(), base.publisher(), base.signatureVerified(), base.artifactSha256(),
                base.pluginJar(), base.dataDirectory(),
                base.startupPolicy(), base.resources(), endpoints, false, base.permissions(),
                Map.copyOf(config), base.builtIn(), base.description(), base.configurationSchema(),
                base.inference(), base.configurationUi(), base.endpointCapabilities(),
                base.developmentUnsigned());
    }

    /** Removes only the inference binding; process removal remains owned by PluginManager. */
    public synchronized void unregisterRuntime() {
        activeRuntime = null;
        resetLoadedState();
    }

    private void rollbackRuntimeSwitch(
            InferenceCatalogPort.RuntimeInstallation previousRuntime,
            Optional<ServicePluginDefinition> previousDefinition,
            boolean restart,
            RuntimeException originalFailure) {
        try {
            if (processes.definition(PLUGIN_ID).isPresent()) processes.stop(PLUGIN_ID);
            if (previousDefinition.isPresent()) {
                processes.register(previousDefinition.get());
                activeRuntime = previousRuntime;
                resetLoadedState();
                if (restart) {
                    processes.start(PLUGIN_ID);
                    restorePublishedProfiles();
                }
            } else {
                if (processes.definition(PLUGIN_ID).isPresent()) processes.unregister(PLUGIN_ID);
                activeRuntime = previousRuntime;
                resetLoadedState();
            }
        } catch (RuntimeException rollbackFailure) {
            originalFailure.addSuppressed(rollbackFailure);
        }
    }

    private void resetLoadedState() {
        loaded.clear();
        loadedProcessPid = 0;
    }

    private String apiKeyPolicies(List<InferenceCatalogPort.ApiKeyRecord> keys) {
        long minute = Instant.now().getEpochSecond() / 60;
        List<Map<String, Object>> policies = keys.stream().map(key -> {
            InferenceCatalogPort.ApiUsage usage = catalog.apiUsage(key.id(), minute)
                    .orElse(new InferenceCatalogPort.ApiUsage(0, 0, 0, 0));
            Map<String, Object> policy = new java.util.LinkedHashMap<>();
            policy.put("keyId", key.id().toString());
            policy.put("prefix", key.prefix());
            policy.put("scopes", key.scopes().stream().map(Enum::name).sorted().toList());
            policy.put("modelAliases", key.modelAliases().stream().sorted().toList());
            policy.put("requestsPerMinute", key.requestsPerMinute());
            policy.put("tokensPerMinute", key.tokensPerMinute());
            policy.put("maxConcurrent", key.maxConcurrent());
            policy.put("initialMinute", minute);
            policy.put("initialRequests", usage.requests());
            policy.put("initialTokens", Math.addExact(usage.promptTokens(), usage.completionTokens()));
            return Map.copyOf(policy);
        }).toList();
        try { return json.writeValueAsString(policies); }
        catch (Exception failure) {
            throw new IllegalStateException("无法生成 Deliverance API Key 策略", failure);
        }
    }

    @Override
    public InferenceChatResponse chat(InferenceChatRequest request) throws InferenceException {
        InferenceModelProfile profile = requireProfile(
                request.profileId(), InferenceModelProfile.Kind.GENERATION);
        ensureLoaded(profile);
        InferencePluginProtocol.ChatRequest wire = chatRequest(request, false);
        WireChatRequest envelope = new WireChatRequest(profile.id().toString(), wire);
        return invoke(request.requestId(), "deliverance/chat", "chat", envelope,
                request.timeout(), response -> map(read(response.payload(), InferencePluginProtocol.ChatResponse.class)));
    }

    @Override
    public StreamSession streamChat(InferenceChatRequest request, Consumer<InferenceStreamEvent> events)
            throws InferenceException {
        java.util.Objects.requireNonNull(events, "events");
        InferenceModelProfile profile = requireProfile(
                request.profileId(), InferenceModelProfile.Kind.GENERATION);
        ensureLoaded(profile);
        CompletableFuture<InferenceChatResponse> completion = new CompletableFuture<>();
        java.util.concurrent.atomic.AtomicBoolean terminal = new java.util.concurrent.atomic.AtomicBoolean();
        events.accept(new InferenceStreamEvent(request.requestId(), InferenceStreamEvent.Type.STARTED,
                "", List.of(), null, null, "", "", Instant.now()));
        ServicePluginInvocationPort.Invocation invocation;
        try {
            invocation = processes.invoke(PLUGIN_ID, "deliverance/chat", "chat", "application/json",
                    json.writeValueAsBytes(new WireChatRequest(profile.id().toString(),
                            chatRequest(request, true))), request.timeout(), event -> {
                        if (terminal.get()) return;
                        try {
                            InferenceStreamEvent mapped = map(read(
                                    event.payload(), InferencePluginProtocol.StreamEvent.class));
                            if (!mapped.terminal()) events.accept(mapped);
                        } catch (Throwable failure) {
                            ServicePluginInvocationPort.Invocation current = requests.get(request.requestId());
                            if (current != null) current.cancel();
                        }
                    });
        } catch (Exception failure) {
            throw inference(failure);
        }
        if (requests.putIfAbsent(request.requestId(), invocation) != null) {
            invocation.cancel();
            throw new InferenceException("duplicate_request", "推理 requestId 重复", false);
        }
        invocation.completion().whenComplete((response, failure) -> {
            requests.remove(request.requestId(), invocation);
            if (!terminal.compareAndSet(false, true)) return;
            if (failure == null) {
                try {
                    InferenceChatResponse value = map(read(
                            response.payload(), InferencePluginProtocol.ChatResponse.class));
                    events.accept(new InferenceStreamEvent(request.requestId(),
                            InferenceStreamEvent.Type.COMPLETE, "", value.toolCalls(), value.usage(),
                            value, "", "", Instant.now()));
                    completion.complete(value);
                } catch (Throwable parseFailure) {
                    terminalFailure(request.requestId(), parseFailure, events, completion);
                }
            } else terminalFailure(request.requestId(), failure, events, completion);
        });
        return new StreamSession() {
            @Override public String requestId() { return request.requestId(); }
            @Override public CompletableFuture<InferenceChatResponse> completion() { return completion; }
            @Override public boolean cancel() {
                if (terminal.get()) return false;
                return DeliveranceServicePluginGateway.this.cancel(request.requestId());
            }
        };
    }

    @Override
    public InferenceEmbeddingResponse embeddings(InferenceEmbeddingRequest request)
            throws InferenceException {
        InferenceModelProfile profile = requireProfile(
                request.profileId(), InferenceModelProfile.Kind.EMBEDDING);
        ensureLoaded(profile);
        WireEmbeddingRequest envelope = new WireEmbeddingRequest(profile.id().toString(),
                new InferencePluginProtocol.EmbeddingRequest(request.requestId(), request.input()));
        return invoke(request.requestId(), "deliverance/embedding", "embedding", envelope,
                request.timeout(), response -> {
                    InferencePluginProtocol.EmbeddingResponse value = read(
                            response.payload(), InferencePluginProtocol.EmbeddingResponse.class);
                    return new InferenceEmbeddingResponse(value.requestId(), value.model(), value.dimensions(),
                            value.embeddings(), usage(value.usage()), Duration.ofMillis(value.inferenceTimeMs()));
                });
    }

    @Override
    public boolean cancel(String requestId) {
        ServicePluginInvocationPort.Invocation invocation = requests.get(requestId);
        return invocation != null && invocation.cancel();
    }

    public void start(InferenceModelProfile profile) throws Exception { ensureLoaded(profile); }

    public void stop(UUID profileId) {
        if (processes.definition(PLUGIN_ID).isEmpty()) {
            loaded.remove(profileId);
            return;
        }
        try {
            ServicePluginInvocationPort.Invocation invocation = processes.invoke(PLUGIN_ID,
                    "deliverance/unload", "unload", "application/json",
                    json.writeValueAsBytes(new WireUnloadRequest(profileId.toString())),
                    Duration.ofSeconds(30), ignored -> { });
            await(invocation);
            loaded.remove(profileId);
        } catch (Exception failure) {
            if (processes.definition(PLUGIN_ID).isEmpty() || processNoLongerOwnsMemory(failure)) {
                loaded.remove(profileId);
                return;
            }
            if (failure instanceof RuntimeException runtime) throw runtime;
            throw new IllegalStateException("无法从 Deliverance 服务插件卸载模型", failure);
        }
    }

    private static boolean processNoLongerOwnsMemory(Exception failure) {
        if (!(failure instanceof ServicePluginInvocationPort.ServicePluginException service)) return false;
        return "service_unavailable".equals(service.code())
                || "service_process_lost".equals(service.code());
    }

    public Optional<InferenceRuntimePort.RuntimeProfileStatus> status(UUID profileId) {
        long pid = servicePid();
        if (pid <= 0 || loadedProcessPid != pid) {
            loaded.clear();
            loadedProcessPid = pid;
            return Optional.empty();
        }
        return Optional.ofNullable(loaded.get(profileId));
    }

    public InferenceRuntimePort.ProfileProbeResult probeDraft(
            InferenceRuntimePort.ProfileProbeCommand command, BooleanSupplier cancelled) throws Exception {
        BooleanSupplier token = cancelled == null ? () -> false : cancelled;
        if (token.getAsBoolean()) throw new CancellationException("模型档案探测已取消");
        InferenceModelAsset asset = requireAsset(command.assetId(), token);
        ensureServiceRunning();
        InferencePluginProtocol.Startup startup = startup(command.temporaryProfileId().toString(), command.name(),
                command.kind(), asset, command.runtimeId(), command.requestedContextLength(),
                command.kind() == InferenceModelProfile.Kind.EMBEDDING ? 0 : 0,
                command.loadParameters(), command.defaultParameters(), true);
        WireLoadResponse loaded = invoke(UUID.randomUUID().toString(), "deliverance/load", "load",
                new WireLoadRequest(command.temporaryProfileId().toString(), startup), START_TIMEOUT,
                response -> read(response.payload(), WireLoadResponse.class));
        try {
            if (token.getAsBoolean()) throw new CancellationException("模型档案探测已取消");
            if (command.kind() == InferenceModelProfile.Kind.EMBEDDING) {
                invoke(UUID.randomUUID().toString(), "deliverance/embedding", "embedding",
                        new WireEmbeddingRequest(command.temporaryProfileId().toString(),
                                new InferencePluginProtocol.EmbeddingRequest(UUID.randomUUID().toString(),
                                        List.of("JavaClaw 本地推理探测"))), Duration.ofMinutes(5),
                        response -> response);
            } else {
                var probe = new InferencePluginProtocol.ChatRequest(UUID.randomUUID().toString(),
                        List.of(new InferencePluginProtocol.Message("USER", "只回复 OK", "", "", List.of())),
                        List.of(), Map.of("maxTokens", 4, "temperature", 0),
                        new InferencePluginProtocol.ToolChoice("AUTO", ""), true, false);
                invoke(UUID.randomUUID().toString(), "deliverance/chat", "chat",
                        new WireChatRequest(command.temporaryProfileId().toString(), probe),
                        Duration.ofMinutes(5), response -> response);
            }
            return new InferenceRuntimePort.ProfileProbeResult(loaded.model().actualContextLength(),
                    loaded.model().actualEmbeddingDimensions(), loaded.model().selectedBackend(),
                    loaded.model().capabilities());
        } finally { stop(command.temporaryProfileId()); }
    }

    public List<String> recentLogs(UUID profileId, int maxLines) {
        return processes.list().stream().filter(value -> value.id().equals(PLUGIN_ID)).findFirst()
                .map(value -> value.recentLogs().subList(
                        Math.max(0, value.recentLogs().size() - Math.max(0, maxLines)),
                        value.recentLogs().size())).orElse(List.of());
    }

    @Override
    public synchronized void reconfigure() {
        InferenceCatalogPort.RuntimeInstallation runtime = activeRuntime;
        if (runtime == null) return;
        boolean running = serviceHealthy();
        if (running) {
            ServicePluginDefinition previous = processes.definition(PLUGIN_ID).orElse(null);
            ServicePluginDefinition candidate = definition(runtime);
            if (previous != null && supportsInvocationLogging()
                    && hotProcessCompatible(previous, candidate)) {
                hotReconfigure(previous, candidate);
                return;
            }
        }
        if (running) processes.stop(PLUGIN_ID);
        registerRuntime(runtime);
        try {
            if (running) {
                processes.start(PLUGIN_ID);
                restorePublishedProfiles();
            }
        } catch (RuntimeException failure) {
            if (serviceHealthy()) processes.stop(PLUGIN_ID);
            throw failure;
        }
    }

    private void hotReconfigure(ServicePluginDefinition previous,
                                ServicePluginDefinition candidate) {
        boolean before = Boolean.parseBoolean(previous.config().get("external.enabled"));
        boolean after = Boolean.parseBoolean(candidate.config().get("external.enabled"));
        try {
            if (before) configureGateway(false);
            processes.hotConfigure(PLUGIN_ID, candidate, Duration.ofSeconds(10));
            processes.register(candidate);
            configureGateway(after);
        } catch (Exception failure) {
            try { configureGateway(false); }
            catch (Exception disableFailure) { failure.addSuppressed(disableFailure); }
            try {
                processes.hotConfigure(PLUGIN_ID, previous, Duration.ofSeconds(10));
                processes.register(previous);
                configureGateway(before);
            } catch (Exception rollbackFailure) {
                failure.addSuppressed(rollbackFailure);
            }
            throw new IllegalStateException("无法热更新 Deliverance 对外接口", failure);
        }
    }

    private void configureGateway(boolean enabled) throws Exception {
        WireGatewayStatus status = invoke(UUID.randomUUID().toString(),
                "deliverance/gateway", "configure", new WireGatewayConfigure(enabled),
                Duration.ofSeconds(10), value -> read(value.payload(), WireGatewayStatus.class));
        if (status.enabled() != enabled) {
            throw new IllegalStateException("Deliverance 对外接口状态未生效");
        }
    }

    private static boolean hotProcessCompatible(
            ServicePluginDefinition previous, ServicePluginDefinition candidate) {
        return previous.id().equals(candidate.id())
                && previous.version().equals(candidate.version())
                && previous.artifactSha256().equals(candidate.artifactSha256())
                && previous.mainClass().equals(candidate.mainClass())
                && previous.resources().equals(candidate.resources())
                && previous.permissions().equals(candidate.permissions());
    }

    @Override
    public String endpoint() {
        var value = catalog.gatewayConfiguration();
        if (!value.enabled()) return "";
        String base = (value.tlsEnabled() ? "https" : "http") + "://"
                + value.bindAddress() + ":" + value.port();
        return InferenceApiServerControlPort.normalizeOpenAiBaseUrl(base);
    }

    @Override
    public InferenceApiServerControlPort.ApiServerStatus status() {
        String configuredEndpoint = endpoint();
        if (configuredEndpoint.isBlank()) {
            return new InferenceApiServerControlPort.ApiServerStatus(
                    InferenceApiServerControlPort.State.DISABLED, "", "");
        }
        Optional<ServicePluginManagementApplicationService.ServicePluginInfo> plugin =
                processes.list().stream().filter(value -> value.id().equals(PLUGIN_ID)).findFirst();
        if (plugin.isEmpty()) {
            return new InferenceApiServerControlPort.ApiServerStatus(
                    InferenceApiServerControlPort.State.CONFIGURED_STOPPED,
                    configuredEndpoint, "Deliverance 服务插件尚未注册");
        }
        ServicePluginManagementApplicationService.State state = plugin.get().state();
        if (state == ServicePluginManagementApplicationService.State.HEALTHY
                || state == ServicePluginManagementApplicationService.State.DEGRADED) {
            return new InferenceApiServerControlPort.ApiServerStatus(
                    InferenceApiServerControlPort.State.AVAILABLE, configuredEndpoint, "");
        }
        if (state == ServicePluginManagementApplicationService.State.FAILED
                || state == ServicePluginManagementApplicationService.State.QUARANTINED) {
            return new InferenceApiServerControlPort.ApiServerStatus(
                    InferenceApiServerControlPort.State.FAILED, configuredEndpoint,
                    plugin.get().lastError());
        }
        return new InferenceApiServerControlPort.ApiServerStatus(
                InferenceApiServerControlPort.State.CONFIGURED_STOPPED,
                configuredEndpoint, "Deliverance 服务插件未运行");
    }

    @Override
    public InferenceApiServerControlPort.ServiceSnapshot serviceSnapshot() {
        Optional<ServicePluginManagementApplicationService.ServicePluginInfo> plugin =
                processes.list().stream().filter(value -> value.id().equals(PLUGIN_ID)).findFirst();
        return plugin.map(value -> new InferenceApiServerControlPort.ServiceSnapshot(
                status(), value.state().name(), value.pid(), value.activeRequests(),
                value.recentLogs(), supportsInvocationLogging())).orElseGet(() ->
                new InferenceApiServerControlPort.ServiceSnapshot(status(), "STOPPED", 0, 0,
                        List.of(), supportsInvocationLogging()));
    }

    @Override
    public synchronized void syncPublishedModels() throws Exception {
        InferenceCatalogPort.RuntimeInstallation runtime = activeRuntime;
        if (runtime == null || processes.definition(PLUGIN_ID).isEmpty()) return;
        // Persist the complete startup catalog into the process definition for the next start.
        processes.register(definition(runtime));
        if (!serviceHealthy()) return;
        WireCatalogSync snapshot = publishedCatalog();
        invoke(UUID.randomUUID().toString(), "deliverance/models", "sync", snapshot,
                Duration.ofSeconds(30), response -> response);
    }

    @Override
    public synchronized void setInvocationLogging(boolean enabled) throws Exception {
        InferenceCatalogPort.RuntimeInstallation runtime = activeRuntime;
        if (runtime == null || processes.definition(PLUGIN_ID).isEmpty()) return;
        if (!supportsInvocationLogging()) {
            throw new IllegalStateException("Deliverance 插件协议低于 1.2");
        }
        processes.register(definition(runtime));
        if (!serviceHealthy()) return;
        WireLoggingStatus response = invoke(UUID.randomUUID().toString(),
                "deliverance/logging", "configure", new WireLoggingConfigure(enabled),
                Duration.ofSeconds(10), value -> read(value.payload(), WireLoggingStatus.class));
        if (response.enabled() != enabled) {
            throw new IllegalStateException("Deliverance 调用日志状态未生效");
        }
    }

    @Override
    public boolean supportsInvocationLogging() {
        InferenceCatalogPort.RuntimeInstallation runtime = activeRuntime;
        return runtime != null && runtime.manifest().protocol().major() == 1
                && runtime.manifest().protocol().minor() >= 2;
    }

    private synchronized void ensureLoaded(InferenceModelProfile profile) throws InferenceException {
        if (status(profile.id()).isPresent()) return;
        InferenceModelAsset asset = requireAsset(profile.assetId(), () -> Thread.currentThread().isInterrupted());
        ensureServiceRunning();
        try {
            InferencePluginProtocol.Startup startup = startup(profile.id().toString(), profile.name(), profile.kind(),
                    asset, profile.runtimeId(), profile.contextLength(), profile.embeddingDimensions(),
                    profile.loadParameters(), profile.defaultParameters(), false);
            WireLoadResponse response = invoke(UUID.randomUUID().toString(),
                    "deliverance/load", "load", new WireLoadRequest(profile.id().toString(), startup),
                    START_TIMEOUT, value -> read(value.payload(), WireLoadResponse.class));
            WireModelStatus model = response.model();
            loaded.put(profile.id(), new InferenceRuntimePort.RuntimeProfileStatus(profile.id(), true,
                    model.actualContextLength(), model.actualEmbeddingDimensions(),
                    model.selectedBackend(), model.capabilities()));
            loadedProcessPid = servicePid();
            syncPublishedModels();
        } catch (Exception failure) { throw inference(failure); }
    }

    private InferenceModelAsset requireAsset(UUID id, BooleanSupplier cancelled) throws InferenceException {
        InferenceModelAsset asset = catalog.asset(id)
                .filter(value -> value.state() == InferenceModelAsset.State.READY)
                .orElseThrow(() -> new InferenceException(
                        "asset_unavailable", "模型资产尚未准备完成", false));
        try { assets.verifyForColdStart(asset, cancelled); }
        catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new InferenceException("cancelled", "模型资产校验已取消", false, interrupted);
        } catch (Exception failure) {
            throw new InferenceException("asset_integrity_failed",
                    "模型资产完整性校验失败", false, failure);
        }
        return asset;
    }

    private void ensureServiceRunning() throws InferenceException {
        if (activeRuntime == null) throw new InferenceException(
                "runtime_unavailable", "Deliverance 服务插件运行时尚未注册", false);
        if (serviceHealthy()) return;
        try { processes.start(PLUGIN_ID); }
        catch (Exception failure) { throw inference(failure); }
    }

    private boolean serviceHealthy() {
        return processes.list().stream().filter(value -> value.id().equals(PLUGIN_ID)).findFirst()
                .map(value -> value.state() == ServicePluginManagementApplicationService.State.HEALTHY
                        || value.state() == ServicePluginManagementApplicationService.State.DEGRADED)
                .orElse(false);
    }

    private long servicePid() {
        return processes.list().stream().filter(value -> value.id().equals(PLUGIN_ID)).findFirst()
                .filter(value -> value.state() == ServicePluginManagementApplicationService.State.HEALTHY
                        || value.state() == ServicePluginManagementApplicationService.State.DEGRADED)
                .map(ServicePluginManagementApplicationService.ServicePluginInfo::pid)
                .orElse(0L);
    }

    public synchronized void restorePublishedProfiles() {
        loaded.clear();
        loadedProcessPid = servicePid();
        try { syncPublishedModels(); }
        catch (Exception failure) {
            throw new IllegalStateException("无法恢复 Deliverance 模型目录", failure);
        }
    }

    private WireCatalogSync publishedCatalog() {
        Map<String, String> aliases = new java.util.LinkedHashMap<>();
        Map<String, WireProfileRegistration> profiles = new java.util.LinkedHashMap<>();
        for (InferenceCatalogPort.PublishedModel published : catalog.publishedModels()) {
            if (!published.enabled()) continue;
            InferenceModelProfile profile = catalog.profile(published.profileId())
                    .filter(value -> value.state() == InferenceModelProfile.State.READY)
                    .orElseThrow(() -> new IllegalStateException(
                            "已发布模型档案不可用: " + published.profileId()));
            InferenceModelAsset asset = catalog.asset(profile.assetId())
                    .filter(value -> value.state() == InferenceModelAsset.State.READY)
                    .orElseThrow(() -> new IllegalStateException(
                            "已发布模型资产不可用: " + profile.assetId()));
            InferencePluginProtocol.Startup startup = startup(
                    profile.id().toString(), profile.name(), profile.kind(), asset,
                    profile.runtimeId(), profile.contextLength(), profile.embeddingDimensions(),
                    profile.loadParameters(), profile.defaultParameters(), false);
            profiles.put(profile.id().toString(),
                    new WireProfileRegistration(profile.id().toString(), startup));
            aliases.put(published.alias(), profile.id().toString());
        }
        return new WireCatalogSync(List.copyOf(profiles.values()), Map.copyOf(aliases));
    }

    private InferencePluginProtocol.Startup startup(
            String profileId, String name, InferenceModelProfile.Kind kind,
            InferenceModelAsset asset, String runtimeId, int contextLength, int embeddingDimensions,
            Map<String, Object> load, Map<String, Object> defaults, boolean probe) {
        Path model = Path.of(asset.location()).toAbsolutePath().normalize();
        if (!Files.isDirectory(model, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("模型资产目录不存在");
        }
        return new InferencePluginProtocol.Startup(runtimeId, model.toString(), kind.name(), name,
                contextLength, embeddingDimensions, load, defaults, probe);
    }

    private InferenceModelProfile requireProfile(UUID id, InferenceModelProfile.Kind kind)
            throws InferenceException {
        InferenceModelProfile value = catalog.profile(id).orElseThrow(() ->
                new InferenceException("profile_not_found", "本地模型档案不存在", false));
        if (value.kind() != kind) throw new InferenceException(
                "profile_kind_mismatch", "本地模型档案类型不匹配", false);
        if (value.state() != InferenceModelProfile.State.READY
                && value.state() != InferenceModelProfile.State.VERIFYING) {
            throw new InferenceException("profile_not_ready", "本地模型档案尚未通过加载探测", false);
        }
        return value;
    }

    private <T> T invoke(String inferenceRequestId, String serviceId, String operation,
                         Object payload, Duration timeout,
                         java.util.function.Function<ServicePluginInvocationPort.Response, T> mapper)
            throws InferenceException {
        ServicePluginInvocationPort.Invocation invocation;
        try {
            invocation = processes.invoke(PLUGIN_ID, serviceId, operation, "application/json",
                    json.writeValueAsBytes(payload), timeout, ignored -> { });
        } catch (Exception failure) { throw inference(failure); }
        if (requests.putIfAbsent(inferenceRequestId, invocation) != null) {
            invocation.cancel();
            throw new InferenceException("duplicate_request", "推理 requestId 重复", false);
        }
        try { return mapper.apply(await(invocation)); }
        catch (Throwable failure) { throw inference(failure); }
        finally { requests.remove(inferenceRequestId, invocation); }
    }

    private static ServicePluginInvocationPort.Response await(
            ServicePluginInvocationPort.Invocation invocation) throws Exception {
        try { return invocation.completion().get(); }
        catch (InterruptedException interrupted) {
            invocation.cancel();
            Thread.currentThread().interrupt();
            throw new CancellationException("服务插件请求已取消");
        } catch (ExecutionException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof Exception exception) throw exception;
            throw new IllegalStateException(cause);
        }
    }

    private InferenceException inference(Throwable failure) {
        Throwable value = failure instanceof java.util.concurrent.CompletionException completion
                ? completion.getCause() : failure;
        if (value instanceof InferenceException inference) return inference;
        if (value instanceof ServicePluginInvocationPort.ServicePluginException service) {
            InferenceUsage usage = usageFromError(service.payload());
            return new InferenceException(service.code(), service.getMessage(), service.retryable(),
                    usage, service);
        }
        if (value instanceof CancellationException || value instanceof InterruptedException) {
            return new InferenceException("cancelled", "本地推理请求已取消", false,
                    new InferenceUsage(0, 0), value instanceof Exception exception ? exception : null);
        }
        return new InferenceException("service_plugin_error",
                value == null || value.getMessage() == null ? "Deliverance 服务插件调用失败"
                        : value.getMessage(), true,
                value instanceof Exception exception ? exception : null);
    }

    private InferenceUsage usageFromError(byte[] payload) {
        try {
            WireErrorUsage value = read(payload, WireErrorUsage.class);
            return usage(value.usage());
        } catch (Exception ignored) { return new InferenceUsage(0, 0); }
    }

    private void terminalFailure(String requestId, Throwable failure,
                                 Consumer<InferenceStreamEvent> events,
                                 CompletableFuture<InferenceChatResponse> completion) {
        InferenceException mapped = inference(failure);
        InferenceStreamEvent.Type type = "cancelled".equals(mapped.code())
                ? InferenceStreamEvent.Type.CANCELLED : InferenceStreamEvent.Type.ERROR;
        events.accept(new InferenceStreamEvent(requestId, type, "", List.of(), mapped.usage(),
                null, mapped.code(), mapped.getMessage(), Instant.now()));
        completion.completeExceptionally(mapped);
    }

    private InferencePluginProtocol.ChatRequest chatRequest(InferenceChatRequest value, boolean stream) {
        return new InferencePluginProtocol.ChatRequest(value.requestId(), value.messages().stream().map(message ->
                new InferencePluginProtocol.Message(message.role().name(), message.content(),
                        message.reasoningContent(), message.toolCallId(), message.toolCalls().stream()
                        .map(call -> new InferencePluginProtocol.ToolCall(call.id(), call.name(),
                                call.argumentsJson())).toList())).toList(),
                value.tools().stream().map(tool -> new InferencePluginProtocol.Tool(
                        tool.name(), tool.description(), tool.inputSchema())).toList(),
                value.parameters(), new InferencePluginProtocol.ToolChoice(
                value.toolChoice().mode().name(), value.toolChoice().toolName()),
                value.parallelToolCalls(), stream);
    }

    InferenceChatResponse map(InferencePluginProtocol.ChatResponse value) {
        List<InferenceToolCall> toolCalls = tools(value.toolCalls());
        String content = value.content();
        InferenceChatResponse.FinishReason finishReason =
                InferenceChatResponse.FinishReason.valueOf(value.finishReason());
        if (toolCalls.isEmpty()) {
            LegacyToolCalls legacy = legacyToolCalls(value.requestId(), content);
            if (!legacy.calls().isEmpty()) {
                toolCalls = legacy.calls();
                content = legacy.content();
                finishReason = InferenceChatResponse.FinishReason.TOOL_CALLS;
            }
        }
        return new InferenceChatResponse(value.requestId(), value.model(), content,
                value.reasoningContent(), toolCalls, finishReason, usage(value.usage()),
                Duration.ofMillis(value.queueTimeMs()), Duration.ofMillis(value.inferenceTimeMs()));
    }

    private LegacyToolCalls legacyToolCalls(String requestId, String content) {
        if (content == null || !content.contains("<tool_call>")) {
            return new LegacyToolCalls(content == null ? "" : content, List.of());
        }
        final String opening = "<tool_call>";
        final String closing = "</tool_call>";
        StringBuilder visible = new StringBuilder(content.length());
        List<InferenceToolCall> calls = new ArrayList<>();
        int cursor = 0;
        while (calls.size() < 64) {
            int start = content.indexOf(opening, cursor);
            if (start < 0) break;
            int end = content.indexOf(closing, start + opening.length());
            if (end < 0) break;
            Optional<InferenceToolCall> parsed = legacyToolCall(requestId, calls.size(),
                    content.substring(start + opening.length(), end));
            if (parsed.isPresent()) {
                visible.append(content, cursor, start);
                calls.add(parsed.orElseThrow());
            } else {
                visible.append(content, cursor, end + closing.length());
            }
            cursor = end + closing.length();
        }
        visible.append(content, cursor, content.length());
        return calls.isEmpty()
                ? new LegacyToolCalls(content, List.of())
                : new LegacyToolCalls(visible.toString().strip(), List.copyOf(calls));
    }

    private Optional<InferenceToolCall> legacyToolCall(
            String requestId, int index, String payload) {
        try {
            var root = json.readTree(payload);
            String name = root.path("name").asText("").strip();
            var arguments = root.get("arguments");
            if (name.isBlank() || arguments == null) return Optional.empty();
            String argumentsJson = arguments.isTextual()
                    ? arguments.textValue() : json.writeValueAsString(arguments);
            if (!json.readTree(argumentsJson).isObject()) return Optional.empty();
            String seed = requestId + ":" + index + ":" + name + ":" + argumentsJson;
            String id = "call_" + UUID.nameUUIDFromBytes(
                    seed.getBytes(StandardCharsets.UTF_8)).toString().replace("-", "");
            return Optional.of(new InferenceToolCall(id, name, argumentsJson));
        } catch (Exception malformed) {
            return Optional.empty();
        }
    }

    private InferenceStreamEvent map(InferencePluginProtocol.StreamEvent value) {
        return new InferenceStreamEvent(value.requestId(), InferenceStreamEvent.Type.valueOf(value.type()),
                value.text(), tools(value.toolCalls()), value.usage() == null ? null : usage(value.usage()),
                value.response() == null ? null : map(value.response()), value.errorCode(),
                value.errorMessage(), Instant.ofEpochMilli(value.timestampEpochMilli()));
    }

    private static List<InferenceToolCall> tools(List<InferencePluginProtocol.ToolCall> values) {
        return values == null ? List.of() : values.stream().map(value ->
                new InferenceToolCall(value.id(), value.name(), value.argumentsJson())).toList();
    }

    private static InferenceUsage usage(InferencePluginProtocol.Usage value) {
        return value == null ? new InferenceUsage(0, 0)
                : new InferenceUsage(value.promptTokens(), value.completionTokens());
    }

    private <T> T read(byte[] bytes, Class<T> type) {
        try { return json.readValue(bytes, type); }
        catch (Exception failure) { throw new IllegalStateException("服务插件响应格式无效", failure); }
    }

    private record WireLoadRequest(String profileId, InferencePluginProtocol.Startup startup) { }
    private record WireUnloadRequest(String profileId) { }
    private record WireChatRequest(String profileId, InferencePluginProtocol.ChatRequest request) { }
    private record WireEmbeddingRequest(String profileId, InferencePluginProtocol.EmbeddingRequest request) { }
    private record WireProfileRegistration(String profileId, InferencePluginProtocol.Startup startup) { }
    private record WireCatalogSync(List<WireProfileRegistration> profiles,
                                   Map<String, String> aliases) { }
    private record WireLoggingConfigure(boolean enabled) { }
    private record WireLoggingStatus(boolean enabled) { }
    private record WireGatewayConfigure(boolean enabled) { }
    private record WireGatewayStatus(boolean enabled) { }
    private record WireModelStatus(String profileId, String name, String kind, boolean loaded,
                                   int actualContextLength, int actualEmbeddingDimensions,
                                   String selectedBackend, Set<String> capabilities) { }
    private record WireLoadResponse(WireModelStatus model) { }
    private record WireErrorUsage(InferencePluginProtocol.Usage usage) { }
    private record LegacyToolCalls(String content, List<InferenceToolCall> calls) { }
}
