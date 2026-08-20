package com.javaclaw.application.inference;

import com.javaclaw.inference.api.InferenceModelAsset;
import com.javaclaw.inference.api.InferenceModelProfile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** 快速设置编排；资产、运行时、API 和工作区模型配置仍保持各自原有边界。 */
public final class LocalInferenceQuickSetupUseCase
        implements LocalInferenceQuickSetupApplicationService {

    private static final Logger log = LoggerFactory.getLogger(LocalInferenceQuickSetupUseCase.class);
    private static final int FIRST_PORT = 18080;
    private static final int LAST_PORT = 18089;

    private final InferenceCatalogPort catalog;
    private final InferenceManagementApplicationService management;
    private final InferenceRuntimePort runtimes;
    private final InferenceModelMetadataPort metadata;
    private final InferenceApiServerControlPort apiServer;
    private final LocalInferenceNetworkPort network;
    private final Object loadLock = new Object();

    public LocalInferenceQuickSetupUseCase(
            InferenceCatalogPort catalog,
            InferenceManagementApplicationService management,
            InferenceRuntimePort runtimes,
            InferenceModelMetadataPort metadata,
            InferenceApiServerControlPort apiServer,
            LocalInferenceNetworkPort network) {
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.management = Objects.requireNonNull(management, "management");
        this.runtimes = Objects.requireNonNull(runtimes, "runtimes");
        this.metadata = Objects.requireNonNull(metadata, "metadata");
        this.apiServer = Objects.requireNonNull(apiServer, "apiServer");
        this.network = Objects.requireNonNull(network, "network");
    }

    @Override
    public QuickSnapshot quickSnapshot() {
        Optional<InferenceCatalogPort.RuntimeInstallation> activeRuntime = activeRuntime();
        List<SupportedModelType> supportedTypes = activeRuntime
                .map(value -> supportedModelTypes(value.manifest())).orElseGet(List::of);
        Set<String> supportedTypeIds = supportedTypes.stream()
                .map(SupportedModelType::id).collect(java.util.stream.Collectors.toUnmodifiableSet());
        List<InferenceCatalogPort.ModelProfileSummary> generation =
                catalog.profileSummaries(InferenceModelProfile.Kind.GENERATION);
        Optional<InferenceCatalogPort.PublishedModel> published =
                catalog.publishedModel(DEFAULT_ALIAS).filter(InferenceCatalogPort.PublishedModel::enabled);
        UUID publishedId = published.map(InferenceCatalogPort.PublishedModel::profileId).orElse(null);
        InferenceApiServerControlPort.ApiServerStatus api = apiServer.status();

        Map<UUID, InferenceCatalogPort.ModelProfileSummary> latest = new LinkedHashMap<>();
        for (InferenceCatalogPort.ModelProfileSummary profile : generation) {
            latest.putIfAbsent(profile.assetId(), profile);
        }
        if (publishedId != null) {
            generation.stream().filter(profile -> profile.id().equals(publishedId)).findFirst()
                    .ifPresent(profile -> latest.put(profile.assetId(), profile));
        }

        List<LocalModel> models = new ArrayList<>();
        for (InferenceCatalogPort.ModelAssetSummary unresolved : catalog.assetSummaries()) {
            InferenceCatalogPort.ModelAssetSummary asset = resolveLegacyModelType(unresolved);
            if (!supportedTypeIds.contains(asset.modelType())) continue;
            InferenceCatalogPort.ModelProfileSummary profile = latest.get(asset.id());
            Status status = status(asset, profile, publishedId, api.available());
            String failure = !asset.failure().isBlank() ? asset.failure()
                    : profile == null ? "" : profile.failure();
            models.add(new LocalModel(asset.id(), asset.displayName(), asset.modelType(), asset.sizeBytes(),
                    asset.state(), profile == null ? null : profile.id(), status, failure));
        }
        InferenceCatalogPort.GatewayConfiguration gateway = catalog.gatewayConfiguration();
        boolean keyConfigured = catalog.hasActiveApiKey(
                Set.of(InferenceCatalogPort.ApiScope.MODELS_READ,
                        InferenceCatalogPort.ApiScope.CHAT_INVOKE), DEFAULT_ALIAS);
        return new QuickSnapshot(models, publishedId, DEFAULT_ALIAS, api.endpoint(),
                gateway.enabled(), keyConfigured,
                gateway.tlsEnabled() || gateway.allowInsecureLanWithoutTls(),
                api.state(), supportedTypes);
    }

    private InferenceCatalogPort.ModelAssetSummary resolveLegacyModelType(
            InferenceCatalogPort.ModelAssetSummary summary) {
        if (!"unknown".equals(summary.modelType())) return summary;
        try {
            InferenceModelAsset asset = catalog.asset(summary.id()).orElseThrow();
            String modelType = metadata.inspectModel(Path.of(asset.location()), () -> false).modelType();
            InferenceModelAsset updated = new InferenceModelAsset(
                    asset.id(), asset.source(), asset.displayName(), modelType,
                    asset.contentSha256(), asset.location(), asset.huggingFaceRepository(),
                    asset.huggingFaceCommit(), asset.files(), asset.sizeBytes(), asset.state(),
                    asset.failure(), asset.createdAt());
            catalog.saveAsset(updated);
            return new InferenceCatalogPort.ModelAssetSummary(summary.id(), summary.displayName(),
                    modelType, summary.sizeBytes(), summary.state(), summary.failure());
        } catch (Exception failure) {
            log.debug("无法回填本地模型类型: {}", summary.id(), failure);
            return summary;
        }
    }

    @Override
    public InferenceModelAsset importLocalModel(
            Path source,
            Consumer<InferenceAssetPreparationPort.Progress> progress,
            BooleanSupplier cancelled) throws Exception {
        BooleanSupplier cancellation = cancelled == null ? () -> false : cancelled;
        InferenceCatalogPort.RuntimeInstallation runtime = activeRuntime().orElseThrow(() ->
                new IllegalStateException("没有可用的 Deliverance 活动运行时"));
        InferenceModelMetadataPort.ModelMetadata model = metadata.inspectModel(source, cancellation);
        requireSupported(runtime.manifest(), model.modelType());
        return management.importLocal(source, progress, cancelled);
    }

    @Override
    public InferenceManagementApplicationService.ProfileDraft defaultDraft(UUID assetId) {
        InferenceModelAsset asset = catalog.asset(Objects.requireNonNull(assetId, "assetId"))
                .filter(value -> value.state() == InferenceModelAsset.State.READY)
                .orElseThrow(() -> new IllegalArgumentException("请选择已导入完成的本地模型"));
        InferenceCatalogPort.RuntimeInstallation runtime = activeRuntime().orElseThrow(() ->
                new IllegalStateException("没有可用的 Deliverance 活动运行时"));
        requireSupported(runtime.manifest(), asset.modelType());
        Optional<InferenceModelProfile> existing = catalog.profiles().stream()
                .filter(profile -> profile.kind() == InferenceModelProfile.Kind.GENERATION)
                .filter(profile -> profile.assetId().equals(assetId))
                .filter(profile -> profile.runtimeId().equals(runtime.manifest().runtimeId()))
                .filter(profile -> profile.state() == InferenceModelProfile.State.READY)
                .findFirst();
        Map<String, Object> load = existing.map(InferenceModelProfile::loadParameters)
                .orElseGet(() -> schemaDefaults(runtime.manifest().parameterSchema(), "load"));
        Map<String, Object> generation = existing.map(InferenceModelProfile::defaultParameters)
                .orElseGet(() -> schemaDefaults(runtime.manifest().parameterSchema(), "generation"));
        return new InferenceManagementApplicationService.ProfileDraft(
                UUID.randomUUID(), asset.displayName(), InferenceModelProfile.Kind.GENERATION,
                asset.id(), runtime.manifest().runtimeId(), load, generation, 0);
    }

    @Override
    public LoadResult loadAndPublish(
            LoadCommand command,
            Consumer<Progress> progress,
            BooleanSupplier cancelled) throws Exception {
        Consumer<Progress> updates = progress == null ? ignored -> { } : progress;
        BooleanSupplier cancellation = cancelled == null ? () -> false : cancelled;
        synchronized (loadLock) {
            throwIfCancelled(cancellation);
            InferenceManagementApplicationService.ProfileDraft draft = command.draft();
            updates.accept(new Progress("验证并探测模型", 0.1));
            InferenceModelProfile profile = identicalProfile(draft).orElseGet(() -> null);
            boolean createdProfile = false;
            if (profile == null) {
                UUID id = catalog.profile(draft.profileId()).isPresent()
                        ? UUID.randomUUID() : draft.profileId();
                var isolated = new InferenceManagementApplicationService.ProfileDraft(
                        id, draft.name(), draft.kind(), draft.assetId(), draft.runtimeId(),
                        draft.loadParameters(), draft.defaultParameters(), draft.contextLimit());
                profile = management.saveAndVerifyProfile(isolated, cancellation);
                createdProfile = true;
            }
            throwIfCancelled(cancellation);
            updates.accept(new Progress("预热模型进程", 0.6));
            management.startProfile(profile.id());

            InferenceCatalogPort.PublishedModel previousPublished =
                    catalog.publishedModel(DEFAULT_ALIAS).orElse(null);
            InferenceCatalogPort.GatewayConfiguration previousGateway = catalog.gatewayConfiguration();
            InferenceManagementApplicationService.CreatedApiKey createdKey = null;
            boolean publishedChanged = false;
            boolean gatewayChanged = false;
            try {
                throwIfCancelled(cancellation);
                if (catalog.apiKeys().stream().noneMatch(this::quickKey)) {
                    createdKey = management.createApiKey("本地推理快速访问",
                            Set.of(InferenceCatalogPort.ApiScope.MODELS_READ,
                                    InferenceCatalogPort.ApiScope.CHAT_INVOKE),
                            Set.of(DEFAULT_ALIAS), 60, 100_000, 1);
                }
                updates.accept(new Progress("发布 OpenAI 兼容 API", 0.82));
                management.publish(DEFAULT_ALIAS, profile.id());
                publishedChanged = true;
                if (!previousGateway.enabled()) {
                    InferenceCatalogPort.GatewayConfiguration target = enabledGateway(
                            previousGateway, command.insecureLanConfirmed());
                    management.saveGateway(target);
                    gatewayChanged = true;
                }
                InferenceApiServerControlPort.ApiServerStatus api = apiServer.status();
                String endpoint = api.endpoint();
                if (!api.available()) throw new IllegalStateException(
                        "本地推理 API 未成功启动：" + api.state());
                InferenceRuntimePort.RuntimeProfileStatus runtimeStatus = runtimes.status(profile.id())
                        .orElse(new InferenceRuntimePort.RuntimeProfileStatus(profile.id(), true,
                                profile.contextLength(), 0,
                                String.valueOf(profile.loadParameters().getOrDefault("tensorBackend", "auto")),
                                Set.of()));
                updates.accept(new Progress("本地模型与 API 已就绪", 1));
                return new LoadResult(profile.id(), DEFAULT_ALIAS, endpoint,
                        runtimeStatus.actualContextLength(), runtimeStatus.selectedBackend(),
                        createdKey == null ? "" : createdKey.secret());
            } catch (Throwable failure) {
                rollback(previousPublished, previousGateway, publishedChanged,
                        gatewayChanged, createdKey, profile, createdProfile, failure);
                if (failure instanceof Exception exception) throw exception;
                throw (Error) failure;
            }
        }
    }

    private Status status(InferenceCatalogPort.ModelAssetSummary asset,
                          InferenceCatalogPort.ModelProfileSummary profile, UUID publishedId,
                          boolean apiAvailable) {
        if (asset.state() == InferenceModelAsset.State.FAILED
                || profile != null && profile.state() == InferenceModelProfile.State.FAILED) {
            return Status.FAILED;
        }
        if (profile == null) return Status.IMPORTED;
        if (runtimes.status(profile.id()).map(InferenceRuntimePort.RuntimeProfileStatus::running).orElse(false)) {
            return Status.RUNNING;
        }
        return profile.id().equals(publishedId) && apiAvailable
                ? Status.API_AVAILABLE : Status.READY;
    }

    private Optional<InferenceCatalogPort.RuntimeInstallation> activeRuntime() {
        return catalog.runtimes().stream()
                .filter(InferenceCatalogPort.RuntimeInstallation::active)
                .filter(value -> "deliverance".equalsIgnoreCase(value.manifest().engine()))
                .findFirst();
    }

    private List<SupportedModelType> supportedModelTypes(
            com.javaclaw.inference.api.InferenceRuntimeManifest manifest) {
        return runtimes.supportedModelTypes(manifest, InferenceModelProfile.Kind.GENERATION).stream()
                .map(SupportedModelType::fromId)
                .sorted(java.util.Comparator.comparing(SupportedModelType::displayName))
                .toList();
    }

    private void requireSupported(
            com.javaclaw.inference.api.InferenceRuntimeManifest manifest, String modelType) {
        List<SupportedModelType> supported = supportedModelTypes(manifest);
        if (supported.stream().anyMatch(value -> value.id().equals(modelType))) return;
        String available = supported.isEmpty() ? "无"
                : supported.stream().map(SupportedModelType::displayName)
                .collect(java.util.stream.Collectors.joining("、"));
        if (modelType == null || modelType.isBlank() || "unknown".equals(modelType)) {
            throw new IllegalArgumentException(
                    "无法识别模型类型，请选择包含有效 model_type 的 Hugging Face 模型目录。支持：" + available);
        }
        throw new IllegalArgumentException("不支持模型类型 “" + modelType
                + "”。当前运行时支持：" + available);
    }

    private Optional<InferenceModelProfile> identicalProfile(
            InferenceManagementApplicationService.ProfileDraft draft) {
        return catalog.profiles().stream()
                .filter(profile -> profile.kind() == draft.kind())
                .filter(profile -> profile.assetId().equals(draft.assetId()))
                .filter(profile -> profile.runtimeId().equals(draft.runtimeId()))
                .filter(profile -> profile.state() == InferenceModelProfile.State.READY)
                .filter(profile -> profile.loadParameters().equals(draft.loadParameters()))
                .filter(profile -> profile.defaultParameters().equals(draft.defaultParameters()))
                .filter(profile -> draft.contextLimit() == 0
                        || profile.contextLength() == draft.contextLimit())
                .findFirst();
    }

    private InferenceCatalogPort.GatewayConfiguration enabledGateway(
            InferenceCatalogPort.GatewayConfiguration previous,
            boolean insecureLanConfirmed) {
        String address = previous.bindAddress();
        if (previous.loopbackOnly() && !previous.tlsEnabled()) {
            address = network.preferredPrivateIpv4().orElseThrow(() ->
                    new IllegalStateException("没有找到可用于局域网 API 的私有 IPv4 网卡"));
        }
        boolean insecure = !previous.tlsEnabled() && !isLoopback(address);
        if (insecure && !(previous.allowInsecureLanWithoutTls() || insecureLanConfirmed)) {
            throw new IllegalStateException("启用局域网 HTTP API 前必须确认未加密风险");
        }
        int port = choosePort(address, previous.port());
        return new InferenceCatalogPort.GatewayConfiguration(true, address, port,
                previous.tlsEnabled(), insecure,
                previous.keyStorePath(), previous.encryptedKeyStorePassword(),
                previous.maxRequestBytes(), previous.requestTimeoutSeconds(),
                previous.invocationLoggingEnabled(), Instant.now());
    }

    private int choosePort(String address, int preferred) {
        if (network.portAvailable(address, preferred)) return preferred;
        for (int port = FIRST_PORT; port <= LAST_PORT; port++) {
            if (port != preferred && network.portAvailable(address, port)) return port;
        }
        throw new IllegalStateException("局域网 API 端口 18080–18089 均不可用");
    }

    private void rollback(
            InferenceCatalogPort.PublishedModel previousPublished,
            InferenceCatalogPort.GatewayConfiguration previousGateway,
            boolean publishedChanged,
            boolean gatewayChanged,
            InferenceManagementApplicationService.CreatedApiKey createdKey,
            InferenceModelProfile profile,
            boolean createdProfile,
            Throwable failure) {
        if (gatewayChanged) suppress(failure, () -> management.saveGateway(previousGateway));
        if (publishedChanged) suppress(failure, () -> {
            if (previousPublished == null) management.unpublish(DEFAULT_ALIAS);
            else management.publish(previousPublished.alias(), previousPublished.profileId());
        });
        if (createdKey != null) suppress(failure, () -> management.revokeApiKey(createdKey.metadata().id()));
        if (createdProfile && (previousPublished == null
                || !previousPublished.profileId().equals(profile.id()))) {
            suppress(failure, () -> management.stopProfile(profile.id()));
        }
    }

    private boolean quickKey(InferenceCatalogPort.ApiKeyRecord key) {
        return key.permits(InferenceCatalogPort.ApiScope.MODELS_READ, null)
                && key.permits(InferenceCatalogPort.ApiScope.CHAT_INVOKE, DEFAULT_ALIAS);
    }

    private String apiEndpoint() { return apiServer.status().endpoint(); }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> schemaDefaults(Map<String, Object> schema, String group) {
        Object propertiesValue = schema.get("properties");
        if (!(propertiesValue instanceof Map<?, ?> properties)) return Map.of();
        Object groupValue = properties.get(group);
        if (!(groupValue instanceof Map<?, ?> groupSchema)) return Map.of();
        Object fieldsValue = groupSchema.get("properties");
        if (!(fieldsValue instanceof Map<?, ?> fields)) return Map.of();
        LinkedHashMap<String, Object> defaults = new LinkedHashMap<>();
        fields.forEach((name, value) -> {
            if (name instanceof String key && value instanceof Map<?, ?> field
                    && field.containsKey("default")
                    && !Boolean.TRUE.equals(field.get("x-javaclaw-hidden"))) {
                defaults.put(key, copyJsonValue(field.get("default")));
            }
        });
        return Map.copyOf(defaults);
    }

    private static Object copyJsonValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            LinkedHashMap<String, Object> copy = new LinkedHashMap<>();
            map.forEach((key, child) -> copy.put(String.valueOf(key), copyJsonValue(child)));
            return Map.copyOf(copy);
        }
        if (value instanceof List<?> list) return list.stream().map(LocalInferenceQuickSetupUseCase::copyJsonValue).toList();
        return value;
    }

    private static boolean isLoopback(String value) {
        return "127.0.0.1".equals(value) || "::1".equals(value)
                || "localhost".equalsIgnoreCase(value);
    }

    private static void throwIfCancelled(BooleanSupplier cancelled) {
        if (Thread.currentThread().isInterrupted() || cancelled.getAsBoolean()) {
            throw new CancellationException("本地模型加载已取消");
        }
    }

    private static void suppress(Throwable target, ThrowingRunnable rollback) {
        try { rollback.run(); }
        catch (Throwable rollbackFailure) { target.addSuppressed(rollbackFailure); }
    }

    @FunctionalInterface
    private interface ThrowingRunnable { void run() throws Exception; }
}
