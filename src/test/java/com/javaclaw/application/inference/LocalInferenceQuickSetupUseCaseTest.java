package com.javaclaw.application.inference;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.javaclaw.inference.api.InferenceModelAsset;
import com.javaclaw.inference.api.InferenceModelProfile;
import com.javaclaw.inference.api.InferenceRuntimeManifest;
import com.javaclaw.infrastructure.inference.JdbcInferenceCatalog;
import com.javaclaw.platform.data.SchemaInitializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.nio.file.Path;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalInferenceQuickSetupUseCaseTest {

    private JdbcInferenceCatalog catalog;
    private RecordingRuntime runtime;
    private RecordingApiServer server;
    private InferenceManagementUseCase management;
    private InferenceCatalogPort.RuntimeInstallation installation;
    private String detectedModelType;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:quick-inference-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
        new SchemaInitializer(dataSource).initialize();
        catalog = new JdbcInferenceCatalog(new JdbcTemplate(dataSource),
                new DataSourceTransactionManager(dataSource),
                new ObjectMapper().findAndRegisterModules());
        installation = new InferenceCatalogPort.RuntimeInstallation(manifest(), "/tmp/runtime",
                InferenceCatalogPort.RuntimeState.ACTIVE, true, Instant.now());
        catalog.saveRuntime(installation);
        runtime = new RecordingRuntime();
        server = new RecordingApiServer(catalog);
        detectedModelType = "qwen2";
        management = new InferenceManagementUseCase(catalog, new NoopAssets(), runtime,
                InferenceSecretPort.PASSTHROUGH, server);
    }

    @Test
    void derivesManifestDefaultsAndLoadsPublishesWithoutChangingWorkspaceBindings() throws Exception {
        InferenceModelAsset selected = asset("Qwen2.5-0.5B", "a");
        InferenceModelAsset boundAsset = asset("existing", "b");
        catalog.saveAsset(selected);
        catalog.saveAsset(boundAsset);
        InferenceModelProfile bound = readyProfile(boundAsset, "existing");
        catalog.saveProfile(bound);
        catalog.bind("workspace", InferenceCatalogPort.ModelTier.HIGH, bound.id());
        FakeNetwork network = new FakeNetwork("192.168.10.24", Set.of(18080));
        LocalInferenceQuickSetupUseCase useCase = quick(network);

        var draft = useCase.defaultDraft(selected.id());
        assertEquals("Qwen2.5-0.5B", draft.name());
        assertFalse(draft.loadParameters().containsKey("jvmHeapMiB"));
        assertEquals(4, draft.loadParameters().get("workerThreads"));
        assertEquals("auto", draft.loadParameters().get("tensorBackend"));
        assertEquals(10_000, draft.loadParameters().get("kvCacheMaxEntries"));
        assertEquals(0, draft.defaultParameters().get("temperature"));
        assertEquals(1024, draft.defaultParameters().get("maxTokens"));
        assertEquals(42, draft.defaultParameters().get("seed"));
        assertEquals(true, draft.defaultParameters().get("enableThinking"));

        List<LocalInferenceQuickSetupApplicationService.Progress> progress = new ArrayList<>();
        var loaded = useCase.loadAndPublish(
                new LocalInferenceQuickSetupApplicationService.LoadCommand(draft, true),
                progress::add, () -> false);

        assertEquals("local-chat", loaded.alias());
        assertEquals("http://192.168.10.24:18081/v1", loaded.endpoint());
        assertEquals(8192, loaded.actualContextLength());
        assertEquals("jvector", loaded.selectedBackend());
        assertTrue(loaded.apiKey().startsWith("jcl_"));
        assertEquals(loaded.profileId(), catalog.publishedModel("local-chat").orElseThrow().profileId());
        assertEquals(bound.id(), catalog.bindings("workspace").get(InferenceCatalogPort.ModelTier.HIGH));
        assertEquals("192.168.10.24", catalog.gatewayConfiguration().bindAddress());
        assertEquals(18081, catalog.gatewayConfiguration().port());
        assertTrue(catalog.gatewayConfiguration().allowInsecureLanWithoutTls());
        var key = catalog.apiKeys().getFirst();
        assertEquals(60, key.requestsPerMinute());
        assertEquals(100_000, key.tokensPerMinute());
        assertEquals(1, key.maxConcurrent());
        assertEquals(Set.of(InferenceCatalogPort.ApiScope.MODELS_READ,
                InferenceCatalogPort.ApiScope.CHAT_INVOKE), key.scopes());
        assertEquals(Set.of("local-chat"), key.modelAliases());
        assertEquals(1, runtime.started.size());
        assertEquals(1, progress.stream().filter(value -> value.fraction() == 1).count());

        var repeated = useCase.loadAndPublish(
                new LocalInferenceQuickSetupApplicationService.LoadCommand(
                        useCase.defaultDraft(selected.id()), true), ignored -> { }, () -> false);
        assertEquals(loaded.profileId(), repeated.profileId());
        assertEquals("", repeated.apiKey());
        assertEquals(2, catalog.profiles().size(), "相同资产和参数必须复用已验证档案");
        assertEquals(1, catalog.apiKeys().size(), "重复加载不得创建额外密钥");
    }

    @Test
    void refusesUnconfirmedLanHttpAndRestoresPreviousAliasAndGateway() {
        InferenceModelAsset oldAsset = asset("old", "c");
        InferenceModelAsset nextAsset = asset("next", "d");
        catalog.saveAsset(oldAsset);
        catalog.saveAsset(nextAsset);
        InferenceModelProfile old = readyProfile(oldAsset, "old");
        catalog.saveProfile(old);
        catalog.publish(new InferenceCatalogPort.PublishedModel("local-chat", old.id(), true,
                Instant.now(), Instant.now()));
        InferenceCatalogPort.GatewayConfiguration previous = catalog.gatewayConfiguration();
        LocalInferenceQuickSetupUseCase useCase = quick(new FakeNetwork("192.168.1.9", Set.of()));

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> useCase.loadAndPublish(new LocalInferenceQuickSetupApplicationService.LoadCommand(
                        useCase.defaultDraft(nextAsset.id()), false), ignored -> { }, () -> false));

        assertTrue(failure.getMessage().contains("确认未加密风险"));
        assertEquals(old.id(), catalog.publishedModel("local-chat").orElseThrow().profileId());
        InferenceCatalogPort.GatewayConfiguration restored = catalog.gatewayConfiguration();
        assertEquals(previous.enabled(), restored.enabled());
        assertEquals(previous.bindAddress(), restored.bindAddress());
        assertEquals(previous.port(), restored.port());
        assertEquals(previous.tlsEnabled(), restored.tlsEnabled());
        assertEquals(previous.allowInsecureLanWithoutTls(), restored.allowInsecureLanWithoutTls());
        assertTrue(catalog.apiKeys().stream().allMatch(InferenceCatalogPort.ApiKeyRecord::revoked));
        assertFalse(runtime.running.stream().anyMatch(id -> !id.equals(old.id())));
    }

    @Test
    void gatewayStartFailureRollsBackRouteAndKeepsTheOldModelUsable() {
        InferenceModelAsset oldAsset = asset("old", "e");
        InferenceModelAsset nextAsset = asset("next", "f");
        catalog.saveAsset(oldAsset);
        catalog.saveAsset(nextAsset);
        InferenceModelProfile old = readyProfile(oldAsset, "old");
        catalog.saveProfile(old);
        catalog.publish(new InferenceCatalogPort.PublishedModel("local-chat", old.id(), true,
                Instant.now(), Instant.now()));
        server.failNext = true;
        LocalInferenceQuickSetupUseCase useCase = quick(new FakeNetwork("10.0.0.8", Set.of()));

        assertThrows(IllegalStateException.class,
                () -> useCase.loadAndPublish(new LocalInferenceQuickSetupApplicationService.LoadCommand(
                        useCase.defaultDraft(nextAsset.id()), true), ignored -> { }, () -> false));

        assertEquals(old.id(), catalog.publishedModel("local-chat").orElseThrow().profileId());
        assertFalse(catalog.gatewayConfiguration().enabled());
        assertTrue(catalog.apiKeys().stream().allMatch(InferenceCatalogPort.ApiKeyRecord::revoked));
        assertTrue(runtime.stopped.stream().anyMatch(id -> !id.equals(old.id())));
    }

    @Test
    void quickSnapshotIsLightweightAndUsesTlsAsACompletedSecurityDecision() throws Exception {
        InferenceModelAsset local = asset("display-name", "1");
        catalog.saveAsset(local);
        catalog.saveGatewayConfiguration(new InferenceCatalogPort.GatewayConfiguration(false,
                "192.168.1.4", 18443, true, false, "/tmp/server.p12", "encrypted",
                4096, 30, Instant.now()));
        InferenceCatalogPort lightweightOnly = (InferenceCatalogPort) Proxy.newProxyInstance(
                InferenceCatalogPort.class.getClassLoader(),
                new Class<?>[]{InferenceCatalogPort.class}, (proxy, method, arguments) -> {
                    if (Set.of("assets", "profiles", "apiKeys").contains(method.getName())) {
                        throw new AssertionError("快速概览不应读取全量目录: " + method.getName());
                    }
                    try {
                        return method.invoke(catalog, arguments);
                    } catch (InvocationTargetException failure) {
                        throw failure.getCause();
                    }
                });
        LocalInferenceQuickSetupUseCase useCase = new LocalInferenceQuickSetupUseCase(
                lightweightOnly, management, runtime, metadata(), server,
                new FakeNetwork("192.168.1.4", Set.of()));

        var snapshot = useCase.quickSnapshot();

        assertEquals("display-name", snapshot.models().getFirst().displayName());
        assertEquals(LocalInferenceQuickSetupApplicationService.Status.IMPORTED,
                snapshot.models().getFirst().status());
        assertTrue(snapshot.insecureLanConfirmed(), "TLS 配置不应再次显示 HTTP 风险确认");
        assertFalse(snapshot.gatewayEnabled());
        assertEquals("", snapshot.endpoint());
        assertEquals("qwen2", snapshot.supportedModelTypes().getFirst().id());
    }

    @Test
    void filtersUnsupportedCatalogAssetsAndRejectsUnsupportedDirectoriesBeforeCopying() {
        catalog.saveAsset(asset("supported", "c"));
        catalog.saveAsset(asset("unsupported", "d", "phi3"));
        LocalInferenceQuickSetupUseCase useCase = quick(new FakeNetwork("192.168.1.5", Set.of()));

        var snapshot = useCase.quickSnapshot();

        assertEquals(List.of("supported"), snapshot.models().stream()
                .map(LocalInferenceQuickSetupApplicationService.LocalModel::displayName).toList());
        assertTrue(snapshot.supportedModelTypes().stream()
                .anyMatch(value -> value.displayName().contains("Qwen")));

        detectedModelType = "phi3";
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> useCase.importLocalModel(Path.of("/tmp/unsupported-model"),
                        ignored -> { }, () -> false));
        assertTrue(failure.getMessage().contains("不支持模型类型"));
        assertTrue(failure.getMessage().contains("Qwen"));
    }

    @Test
    void lightweightSnapshotBackfillsLegacyAssetsBeforeFiltering() {
        InferenceModelAsset legacy = new InferenceModelAsset(UUID.randomUUID(),
                InferenceModelAsset.Source.LOCAL_DIRECTORY, "legacy", "e".repeat(64),
                "/tmp/legacy-model", "", "", List.of(), 0,
                InferenceModelAsset.State.READY, "", Instant.now());
        catalog.saveAsset(legacy);

        var snapshot = quick(new FakeNetwork("192.168.1.6", Set.of())).quickSnapshot();

        assertEquals(List.of("legacy"), snapshot.models().stream()
                .map(LocalInferenceQuickSetupApplicationService.LocalModel::displayName).toList());
        assertEquals("qwen2", catalog.asset(legacy.id()).orElseThrow().modelType());
    }

    @Test
    void quickSnapshotDistinguishesImportedReadyFailedPublishedAndRunningModels() throws Exception {
        InferenceModelAsset imported = asset("imported", "2");
        InferenceModelAsset failedAsset = new InferenceModelAsset(UUID.randomUUID(),
                InferenceModelAsset.Source.LOCAL_DIRECTORY, "failed-asset", "qwen2", "3".repeat(64),
                "/tmp/failed-asset", "", "", List.of(), 0,
                InferenceModelAsset.State.FAILED, " asset failure ", Instant.now());
        InferenceModelAsset failedProfileAsset = asset("failed-profile", "4");
        InferenceModelAsset readyAsset = asset("ready", "5");
        InferenceModelAsset publishedAsset = asset("published", "6");
        InferenceModelAsset runningAsset = asset("running", "7");
        for (InferenceModelAsset value : List.of(imported, failedAsset, failedProfileAsset,
                readyAsset, publishedAsset, runningAsset)) catalog.saveAsset(value);

        InferenceModelProfile failedProfile = new InferenceModelProfile(UUID.randomUUID(), "failed",
                InferenceModelProfile.Kind.GENERATION, failedProfileAsset.id(),
                installation.manifest().runtimeId(), Map.of(), Map.of(), 1, 0,
                InferenceModelProfile.State.FAILED, " profile failure ", Instant.now(), Instant.now());
        InferenceModelProfile ready = readyProfile(readyAsset, "ready");
        InferenceModelProfile published = readyProfile(publishedAsset, "published");
        InferenceModelProfile running = readyProfile(runningAsset, "running");
        for (InferenceModelProfile value : List.of(failedProfile, ready, published, running)) {
            catalog.saveProfile(value);
        }
        catalog.publish(new InferenceCatalogPort.PublishedModel("local-chat", published.id(), true,
                Instant.now(), Instant.now()));
        runtime.start(running);
        management.createApiKey("quick", Set.of(InferenceCatalogPort.ApiScope.MODELS_READ,
                        InferenceCatalogPort.ApiScope.CHAT_INVOKE), Set.of(), 1, 1, 1);
        server.endpoint = "http://127.0.0.1:18080/";

        var snapshot = quick(new FakeNetwork("192.168.1.2", Set.of())).quickSnapshot();
        Map<String, LocalInferenceQuickSetupApplicationService.LocalModel> byName = snapshot.models()
                .stream().collect(java.util.stream.Collectors.toMap(
                        LocalInferenceQuickSetupApplicationService.LocalModel::displayName,
                        value -> value));
        assertEquals(LocalInferenceQuickSetupApplicationService.Status.IMPORTED,
                byName.get("imported").status());
        assertEquals(LocalInferenceQuickSetupApplicationService.Status.FAILED,
                byName.get("failed-asset").status());
        assertEquals("asset failure", byName.get("failed-asset").failure());
        assertEquals(LocalInferenceQuickSetupApplicationService.Status.FAILED,
                byName.get("failed-profile").status());
        assertEquals("profile failure", byName.get("failed-profile").failure());
        assertEquals(LocalInferenceQuickSetupApplicationService.Status.READY,
                byName.get("ready").status());
        assertEquals(LocalInferenceQuickSetupApplicationService.Status.API_AVAILABLE,
                byName.get("published").status());
        assertEquals(LocalInferenceQuickSetupApplicationService.Status.RUNNING,
                byName.get("running").status());
        assertEquals("http://127.0.0.1:18080/v1", snapshot.endpoint());
        assertTrue(snapshot.apiKeyConfigured());
        assertEquals(1, server.statusCalls, "轻量快照只能查询一次服务进程状态");
    }

    @Test
    void configuredButStoppedApiDoesNotClaimThatPublishedModelIsAvailable() {
        InferenceModelAsset model = asset("configured-stopped", "8");
        catalog.saveAsset(model);
        InferenceModelProfile published = readyProfile(model, "configured-stopped");
        catalog.saveProfile(published);
        catalog.publish(new InferenceCatalogPort.PublishedModel("local-chat", published.id(), true,
                Instant.now(), Instant.now()));
        server.endpoint = "http://127.0.0.1:18080/v1/v1/";
        server.forcedState = InferenceApiServerControlPort.State.CONFIGURED_STOPPED;

        var snapshot = quick(new FakeNetwork("192.168.1.2", Set.of())).quickSnapshot();

        assertEquals(InferenceApiServerControlPort.State.CONFIGURED_STOPPED, snapshot.apiState());
        assertEquals("http://127.0.0.1:18080/v1", snapshot.endpoint());
        assertEquals(LocalInferenceQuickSetupApplicationService.Status.READY,
                snapshot.models().getFirst().status());
    }

    @Test
    void invalidAssetsCancellationAndUnavailableLanCapacityFailBeforeChangingBindings() {
        InferenceModelAsset draftAsset = new InferenceModelAsset(UUID.randomUUID(),
                InferenceModelAsset.Source.LOCAL_DIRECTORY, "draft", "qwen2", "8".repeat(64),
                "/tmp/draft", "", "", List.of(), 0,
                InferenceModelAsset.State.STAGING, "", Instant.now());
        catalog.saveAsset(draftAsset);
        LocalInferenceQuickSetupUseCase useCase = quick(new FakeNetwork("192.168.1.7", Set.of()));
        assertThrows(IllegalArgumentException.class, () -> useCase.defaultDraft(draftAsset.id()));
        assertThrows(IllegalArgumentException.class, () -> useCase.defaultDraft(UUID.randomUUID()));

        InferenceModelAsset ready = asset("cancelled", "9");
        catalog.saveAsset(ready);
        var command = new LocalInferenceQuickSetupApplicationService.LoadCommand(
                useCase.defaultDraft(ready.id()), true);
        assertThrows(java.util.concurrent.CancellationException.class,
                () -> useCase.loadAndPublish(command, null, () -> true));
        assertTrue(catalog.publishedModel("local-chat").isEmpty());

        Set<Integer> unavailable = java.util.stream.IntStream.rangeClosed(18080, 18089)
                .boxed().collect(java.util.stream.Collectors.toSet());
        LocalInferenceQuickSetupUseCase exhausted = quick(
                new FakeNetwork("192.168.1.7", unavailable));
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> exhausted.loadAndPublish(command, null, null));
        assertTrue(failure.getMessage().contains("端口"));
        assertTrue(catalog.publishedModel("local-chat").isEmpty());
        assertTrue(catalog.apiKeys().stream().allMatch(InferenceCatalogPort.ApiKeyRecord::revoked));
    }

    @Test
    void enabledGatewayAndMissingRuntimeStatusUseSafeProfileFallback() throws Exception {
        InferenceModelAsset ready = asset("fallback", "a");
        catalog.saveAsset(ready);
        catalog.saveGatewayConfiguration(new InferenceCatalogPort.GatewayConfiguration(true,
                "127.0.0.1", 18080, false, false, "", "", 4096, 30, Instant.now()));
        server.endpoint = "http://127.0.0.1:18080";
        runtime.exposeStatus = false;
        LocalInferenceQuickSetupUseCase useCase = quick(new FakeNetwork("192.168.1.8", Set.of()));
        var draft = useCase.defaultDraft(ready.id());

        var result = useCase.loadAndPublish(
                new LocalInferenceQuickSetupApplicationService.LoadCommand(draft, false), null, null);

        assertEquals(8192, result.actualContextLength());
        assertEquals("auto", result.selectedBackend());
        assertEquals("127.0.0.1", catalog.gatewayConfiguration().bindAddress());
        assertFalse(catalog.gatewayConfiguration().allowInsecureLanWithoutTls());
    }

    @Test
    void blankApiEndpointRollsBackANewRouteAndStopsTheNewProfile() {
        InferenceModelAsset ready = asset("blank-endpoint", "b");
        catalog.saveAsset(ready);
        server.suppressEndpoint = true;
        LocalInferenceQuickSetupUseCase useCase = quick(new FakeNetwork("10.0.0.4", Set.of()));

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> useCase.loadAndPublish(new LocalInferenceQuickSetupApplicationService.LoadCommand(
                        useCase.defaultDraft(ready.id()), true), null, null));

        assertTrue(failure.getMessage().contains("未成功启动"));
        assertTrue(catalog.publishedModel("local-chat").isEmpty());
        assertFalse(catalog.gatewayConfiguration().enabled());
        assertEquals(1, runtime.stopped.size());
    }

    private LocalInferenceQuickSetupUseCase quick(LocalInferenceNetworkPort network) {
        return new LocalInferenceQuickSetupUseCase(
                catalog, management, runtime, metadata(), server, network);
    }

    private InferenceModelMetadataPort metadata() {
        return (source, cancelled) -> new InferenceModelMetadataPort.ModelMetadata(
                detectedModelType, List.of(detectedModelType + "ForCausalLM"));
    }

    private InferenceModelProfile readyProfile(InferenceModelAsset asset, String name) {
        return new InferenceModelProfile(UUID.randomUUID(), name,
                InferenceModelProfile.Kind.GENERATION, asset.id(),
                installation.manifest().runtimeId(), Map.of(), Map.of(), 2048, 0,
                InferenceModelProfile.State.READY, "", Instant.now(), Instant.now());
    }

    private static InferenceModelAsset asset(String name, String marker) {
        return asset(name, marker, "qwen2");
    }

    private static InferenceModelAsset asset(String name, String marker, String modelType) {
        String digest = marker.repeat(64);
        return new InferenceModelAsset(UUID.randomUUID(),
                InferenceModelAsset.Source.LOCAL_DIRECTORY, name, modelType, digest,
                "/tmp/model-" + marker, "", "",
                List.of(new InferenceModelAsset.AssetFile("config.json", 1, digest)),
                1, InferenceModelAsset.State.READY, "", Instant.now());
    }

    private static InferenceRuntimeManifest manifest() {
        Map<String, Object> load = Map.of("type", "object", "properties", Map.of(
                "jvmHeapMiB", Map.of("type", "integer", "default", 4096,
                        "x-javaclaw-hidden", true),
                "workerThreads", field("integer", 4),
                "tensorBackend", field("string", "auto"),
                "kvCacheMaxEntries", field("integer", 10_000)));
        Map<String, Object> generation = Map.of("type", "object", "properties", Map.of(
                "temperature", field("number", 0),
                "maxTokens", field("integer", 1024),
                "seed", field("integer", 42),
                "enableThinking", field("boolean", true)));
        Map<String, Object> schema = Map.of("type", "object", "properties",
                Map.of("load", load, "generation", generation));
        return new InferenceRuntimeManifest("runtime-quick", "deliverance", "0.0.12", "1",
                new InferenceRuntimeManifest.ProtocolVersion(1, 1), "test", "test", 25,
                Set.of("chat", "exact_usage", "terminal_usage", "cancellation",
                        "model-type:generation:qwen2"), schema,
                List.of(new InferenceRuntimeManifest.RuntimeFile(
                        "deliverance.jar", "9".repeat(64), 1)), true);
    }

    private static Map<String, Object> field(String type, Object defaultValue) {
        return Map.of("type", type, "default", defaultValue);
    }

    private static class NoopAssets
            implements InferenceAssetPreparationPort, InferenceModelMetadataPort {
        @Override public ModelMetadata inspectModel(Path modelDirectory, BooleanSupplier cancelled) {
            return new ModelMetadata("qwen2", List.of("Qwen2ForCausalLM"));
        }
        @Override public HuggingFacePreview previewHuggingFace(
                HuggingFaceRequest request, BooleanSupplier cancelled) {
            throw new UnsupportedOperationException();
        }
        @Override public InferenceModelAsset importLocalDirectory(
                Path source, Consumer<Progress> progress, BooleanSupplier cancelled) {
            throw new UnsupportedOperationException();
        }
        @Override public InferenceModelAsset downloadHuggingFace(
                HuggingFaceRequest request, Consumer<Progress> progress,
                BooleanSupplier cancelled) {
            throw new UnsupportedOperationException();
        }
        @Override public void deleteManagedAsset(InferenceModelAsset asset) { }
    }

    private static final class RecordingRuntime implements InferenceRuntimePort {
        private final Set<UUID> running = new HashSet<>();
        private final List<UUID> started = new ArrayList<>();
        private final List<UUID> stopped = new ArrayList<>();
        private boolean exposeStatus = true;
        @Override public void validateProfile(InferenceModelProfile profile) { }
        @Override public void start(InferenceModelProfile profile) {
            started.add(profile.id());
            running.add(profile.id());
        }
        @Override public void stop(UUID profileId) {
            stopped.add(profileId);
            running.remove(profileId);
        }
        @Override public ProfileProbeResult probeDraft(
                ProfileProbeCommand command, BooleanSupplier cancelled) {
            return new ProfileProbeResult(8192, 0, "jvector", Set.of("chat"));
        }
        @Override public Optional<RuntimeProfileStatus> status(UUID profileId) {
            return exposeStatus && running.contains(profileId)
                    ? Optional.of(new RuntimeProfileStatus(profileId, true, 8192, 0,
                    "jvector", Set.of("chat"))) : Optional.empty();
        }
        @Override public List<String> recentLogs(UUID profileId, int maxLines) { return List.of(); }
    }

    private static final class RecordingApiServer implements InferenceApiServerControlPort {
        private final InferenceCatalogPort catalog;
        private String endpoint = "";
        private boolean failNext;
        private boolean suppressEndpoint;
        private InferenceApiServerControlPort.State forcedState;
        private int statusCalls;
        private RecordingApiServer(InferenceCatalogPort catalog) { this.catalog = catalog; }
        @Override public void reconfigure() {
            if (failNext) {
                failNext = false;
                throw new IllegalStateException("synthetic bind failure");
            }
            InferenceCatalogPort.GatewayConfiguration value = catalog.gatewayConfiguration();
            endpoint = suppressEndpoint ? "" : value.enabled() ? (value.tlsEnabled() ? "https" : "http")
                    + "://" + value.bindAddress() + ":" + value.port() : "";
        }
        @Override public String endpoint() { return endpoint; }
        @Override public ApiServerStatus status() {
            statusCalls++;
            return forcedState == null ? InferenceApiServerControlPort.super.status()
                    : new ApiServerStatus(forcedState, endpoint, "");
        }
    }

    private record FakeNetwork(String address, Set<Integer> unavailable)
            implements LocalInferenceNetworkPort {
        private FakeNetwork {
            unavailable = Set.copyOf(unavailable);
        }
        @Override public Optional<String> preferredPrivateIpv4() { return Optional.of(address); }
        @Override public boolean portAvailable(String bindAddress, int port) {
            return address.equals(bindAddress) && !unavailable.contains(port);
        }
    }
}
