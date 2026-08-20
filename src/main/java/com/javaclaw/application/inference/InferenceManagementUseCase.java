package com.javaclaw.application.inference;

import com.javaclaw.inference.api.InferenceModelAsset;
import com.javaclaw.inference.api.InferenceModelProfile;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** 根 Context 中的本地推理管理用例。 */
public final class InferenceManagementUseCase implements InferenceManagementApplicationService {

    private static final SecureRandom RANDOM = new SecureRandom();
    private final InferenceCatalogPort catalog;
    private final InferenceAssetPreparationPort assets;
    private final InferenceModelMetadataPort metadata;
    private final InferenceRuntimePort runtimes;
    private final InferenceSecretPort secrets;
    private final InferenceApiServerControlPort apiServer;
    private final HuggingFaceModelCatalogPort onlineModels;
    private final InferenceSystemProfilePort systemProfiles;
    private volatile boolean assetsReconciled;

    public InferenceManagementUseCase(
            InferenceCatalogPort catalog,
            InferenceAssetPreparationPort assets,
            InferenceRuntimePort runtimes) {
        this(catalog, assets, metadataFrom(assets), runtimes, InferenceSecretPort.PASSTHROUGH,
                InferenceApiServerControlPort.NOOP, HuggingFaceModelCatalogPort.UNAVAILABLE);
    }

    public InferenceManagementUseCase(
            InferenceCatalogPort catalog,
            InferenceAssetPreparationPort assets,
            InferenceRuntimePort runtimes,
            InferenceSecretPort secrets,
            InferenceApiServerControlPort apiServer) {
        this(catalog, assets, metadataFrom(assets), runtimes, secrets, apiServer,
                HuggingFaceModelCatalogPort.UNAVAILABLE, InferenceSystemProfilePort.CONSERVATIVE);
    }

    public InferenceManagementUseCase(
            InferenceCatalogPort catalog,
            InferenceAssetPreparationPort assets,
            InferenceModelMetadataPort metadata,
            InferenceRuntimePort runtimes,
            InferenceSecretPort secrets,
            InferenceApiServerControlPort apiServer) {
        this(catalog, assets, metadata, runtimes, secrets, apiServer,
                HuggingFaceModelCatalogPort.UNAVAILABLE, InferenceSystemProfilePort.CONSERVATIVE);
    }

    public InferenceManagementUseCase(
            InferenceCatalogPort catalog,
            InferenceAssetPreparationPort assets,
            InferenceModelMetadataPort metadata,
            InferenceRuntimePort runtimes,
            InferenceSecretPort secrets,
            InferenceApiServerControlPort apiServer,
            HuggingFaceModelCatalogPort onlineModels) {
        this(catalog, assets, metadata, runtimes, secrets, apiServer, onlineModels,
                InferenceSystemProfilePort.CONSERVATIVE);
    }

    public InferenceManagementUseCase(
            InferenceCatalogPort catalog,
            InferenceAssetPreparationPort assets,
            InferenceModelMetadataPort metadata,
            InferenceRuntimePort runtimes,
            InferenceSecretPort secrets,
            InferenceApiServerControlPort apiServer,
            HuggingFaceModelCatalogPort onlineModels,
            InferenceSystemProfilePort systemProfiles) {
        this.catalog = java.util.Objects.requireNonNull(catalog, "catalog");
        this.assets = java.util.Objects.requireNonNull(assets, "assets");
        this.metadata = java.util.Objects.requireNonNull(metadata, "metadata");
        this.runtimes = java.util.Objects.requireNonNull(runtimes, "runtimes");
        this.secrets = java.util.Objects.requireNonNull(secrets, "secrets");
        this.apiServer = java.util.Objects.requireNonNull(apiServer, "apiServer");
        this.onlineModels = java.util.Objects.requireNonNull(onlineModels, "onlineModels");
        this.systemProfiles = java.util.Objects.requireNonNull(systemProfiles, "systemProfiles");
    }

    @Override
    public Snapshot snapshot(String workspaceId) {
        reconcileAssets();
        return new Snapshot(catalog.runtimes(), catalog.assets(), catalog.profiles(),
                catalog.bindings(requireText(workspaceId, "工作区 ID")), catalog.publishedModels(),
                catalog.gatewayConfiguration(), catalog.apiKeys());
    }

    @Override
    public Snapshot snapshot(String workspaceId, Projection projection) {
        reconcileAssets();
        java.util.Objects.requireNonNull(projection, "projection");
        String owner = requireText(workspaceId, "工作区 ID");
        return switch (projection) {
            case MODEL_MANAGEMENT -> new Snapshot(catalog.runtimes(), catalog.assets(),
                    catalog.profiles(), catalog.bindings(owner), List.of(),
                    InferenceCatalogPort.GatewayConfiguration.defaults(), List.of());
            case PROFILE_CONFIGURATION -> new Snapshot(catalog.runtimes(), catalog.assets(),
                    catalog.profiles(), catalog.bindings(owner), List.of(),
                    InferenceCatalogPort.GatewayConfiguration.defaults(), List.of());
            case ASSET_CATALOG -> new Snapshot(catalog.runtimes(), catalog.assets(),
                    catalog.profiles(), Map.of(),
                    List.of(), InferenceCatalogPort.GatewayConfiguration.defaults(), List.of());
            case RUNTIME_CATALOG -> new Snapshot(catalog.runtimes(), List.of(), catalog.profiles(),
                    Map.of(), List.of(), InferenceCatalogPort.GatewayConfiguration.defaults(), List.of());
            case API_CONFIGURATION -> new Snapshot(List.of(), List.of(), catalog.profiles(), Map.of(),
                    catalog.publishedModels(), catalog.gatewayConfiguration(), catalog.apiKeys());
            case SERVICE_CONSOLE -> new Snapshot(catalog.runtimes(), catalog.assets(),
                    catalog.profiles(), Map.of(), catalog.publishedModels(),
                    catalog.gatewayConfiguration(), catalog.apiKeys());
        };
    }

    private void reconcileAssets() {
        if (assetsReconciled) return;
        synchronized (this) {
            if (assetsReconciled) return;
            try {
                assets.reconcileManagedAssets();
                assetsReconciled = true;
            } catch (Exception failure) {
                throw new IllegalStateException("无法恢复 Deliverance 本地模型目录", failure);
            }
        }
    }

    @Override
    public java.util.List<InferenceModelProfile> readyProfiles(InferenceModelProfile.Kind kind) {
        java.util.Objects.requireNonNull(kind, "kind");
        return catalog.profiles().stream()
                .filter(profile -> profile.kind() == kind)
                .filter(profile -> profile.state() == InferenceModelProfile.State.READY)
                .toList();
    }

    @Override
    public Map<UUID, InferenceRuntimePort.RuntimeProfileStatus> runtimeStatuses(
            Set<UUID> profileIds) {
        if (profileIds == null || profileIds.isEmpty()) return Map.of();
        Map<UUID, InferenceRuntimePort.RuntimeProfileStatus> result = new java.util.LinkedHashMap<>();
        profileIds.stream().filter(java.util.Objects::nonNull).distinct().forEach(profileId ->
                runtimes.status(profileId).filter(InferenceRuntimePort.RuntimeProfileStatus::running)
                        .ifPresent(status -> result.put(profileId, status)));
        return Map.copyOf(result);
    }

    @Override
    public InferenceModelAsset importLocal(
            Path source, Consumer<InferenceAssetPreparationPort.Progress> progress,
            BooleanSupplier cancelled) throws Exception {
        BooleanSupplier cancellation = safe(cancelled);
        InferenceModelMetadataPort.ModelMetadata inspected = metadata.inspectModel(source, cancellation);
        requireSupportedByAnActiveRuntime(inspected.modelType());
        InferenceModelAsset prepared = assets.importLocalDirectory(source, safe(progress), cancellation);
        requireSupportedByAnActiveRuntime(prepared.modelType());
        catalog.saveAsset(prepared);
        return prepared;
    }

    @Override
    public InferenceModelAsset downloadHuggingFace(
            InferenceAssetPreparationPort.HuggingFaceRequest request,
            Consumer<InferenceAssetPreparationPort.Progress> progress,
            BooleanSupplier cancelled) throws Exception {
        BooleanSupplier cancellation = safe(cancelled);
        InferenceAssetPreparationPort.HuggingFacePreview preview =
                assets.previewHuggingFace(request, cancellation);
        requireSupportedByAnActiveRuntime(preview.modelType());
        InferenceModelAsset prepared = assets.downloadHuggingFace(
                request, safe(progress), cancellation);
        requireSupportedByAnActiveRuntime(prepared.modelType());
        catalog.saveAsset(prepared);
        return prepared;
    }

    @Override
    public HuggingFaceModelCatalogPort.SearchPage searchOnlineModels(
            HuggingFaceModelCatalogPort.SearchRequest request,
            BooleanSupplier cancelled) throws Exception {
        return onlineModels.search(request, supportedModelTypes(), safe(cancelled));
    }

    @Override
    public HuggingFaceModelCatalogPort.SearchPage searchOnlineModels(
            HuggingFaceModelCatalogPort.SearchRequest request,
            Consumer<HuggingFaceModelCatalogPort.SearchProgress> progress,
            BooleanSupplier cancelled) throws Exception {
        return onlineModels.searchIncrementally(
                request, supportedModelTypes(), progress == null ? ignored -> { } : progress,
                safe(cancelled));
    }

    @Override
    public HuggingFaceModelCatalogPort.ModelDetail onlineModelDetail(
            String repository, BooleanSupplier cancelled) throws Exception {
        return onlineModels.detail(repository, supportedModelTypes(), safe(cancelled));
    }

    @Override
    public InferenceModelAsset downloadOnlineModel(
            HuggingFaceModelCatalogPort.ModelDetail model,
            Consumer<InferenceAssetPreparationPort.Progress> progress,
            BooleanSupplier cancelled) throws Exception {
        if (model == null) throw new IllegalArgumentException("在线模型不能为空");
        if (!model.downloadable()) throw new IllegalStateException("门控模型不能匿名下载");
        if (!supportedModelTypes().contains(model.summary().modelType())) {
            throw new IllegalArgumentException("当前 Deliverance 不支持该模型类型");
        }
        InferenceModelAsset asset = downloadHuggingFace(
                new InferenceAssetPreparationPort.HuggingFaceRequest(
                        model.summary().repository(), model.summary().commit(), ""),
                progress, cancelled);
        boolean compatible = asset.huggingFaceCommit().equalsIgnoreCase(model.summary().commit())
                && asset.quantizationType().equalsIgnoreCase(model.summary().quantizationType())
                && asset.sourceSizeBytes() == model.summary().sourceSizeBytes()
                && asset.sizeBytes() == model.summary().quantizedSizeBytes();
        if (!compatible) {
            InferenceModelAsset failed = new InferenceModelAsset(asset.id(), asset.source(),
                    asset.displayName(), asset.modelType(), asset.contentSha256(), asset.location(),
                    asset.huggingFaceRepository(), asset.huggingFaceCommit(), asset.files(),
                    asset.sizeBytes(), InferenceModelAsset.State.FAILED,
                    "下载后的 Deliverance 量化清单与在线详情不一致", asset.createdAt(),
                    asset.artifactMetadata());
            catalog.saveAsset(failed);
            throw new IllegalStateException(failed.failure());
        }
        InferenceModelAsset enriched = new InferenceModelAsset(
                asset.id(), asset.source(), asset.displayName(), asset.modelType(),
                asset.contentSha256(), asset.location(), asset.huggingFaceRepository(),
                asset.huggingFaceCommit(), asset.files(), asset.sizeBytes(), asset.state(),
                asset.failure(), asset.createdAt(), new InferenceModelAsset.ArtifactMetadata(
                "SAFETENSORS", model.summary().quantizationType(),
                model.summary().sourceSizeBytes(), asset.sizeBytes(),
                model.summary().lastModified()));
        catalog.saveAsset(enriched);
        return enriched;
    }

    @Override
    public RecommendedProfile recommendedProfile(
            UUID assetId, InferenceModelProfile.Kind kind,
            BooleanSupplier cancelled) throws Exception {
        if (assetId == null || kind == null) throw new IllegalArgumentException("模型资产和用途不能为空");
        BooleanSupplier cancellation = safe(cancelled);
        InferenceModelAsset asset = catalog.asset(assetId)
                .filter(value -> value.state() == InferenceModelAsset.State.READY)
                .orElseThrow(() -> new IllegalStateException("模型资产尚未准备完成"));
        InferenceModelMetadataPort.ModelMetadata model = metadata.inspectModel(
                Path.of(asset.location()), cancellation);
        InferenceCatalogPort.RuntimeInstallation runtime = catalog.runtimes().stream()
                .filter(value -> value.active()
                        && runtimes.supportedModelTypes(value.manifest(), kind).contains(model.modelType()))
                .findFirst().orElseThrow(() -> new IllegalStateException("没有兼容的活动 Deliverance 运行时"));
        InferenceSystemProfilePort.Recommendation recommendation = systemProfiles.recommend(
                model, asset.artifactMetadata());
        return new RecommendedProfile(runtime.manifest().runtimeId(), recommendation.contextLength(),
                recommendation.loadParameters(), kind == InferenceModelProfile.Kind.GENERATION
                ? recommendation.generationParameters() : Map.of(),
                recommendation.capacity(), recommendation.memory());
    }

    @Override
    public InferenceAssetPreparationPort.HuggingFacePreview previewHuggingFace(
            InferenceAssetPreparationPort.HuggingFaceRequest request,
            BooleanSupplier cancelled) throws Exception {
        return assets.previewHuggingFace(request, safe(cancelled));
    }

    @Override
    public InferenceModelProfile saveAndVerifyProfile(ProfileDraft draft, BooleanSupplier cancelled)
            throws Exception {
        BooleanSupplier cancellation = safe(cancelled);
        InferenceModelAsset asset = catalog.asset(draft.assetId())
                .filter(value -> value.state() == InferenceModelAsset.State.READY)
                .orElseThrow(() -> new IllegalStateException("只能为已准备完成的资产创建档案"));
        asset = resolveLegacyModelType(asset, cancellation);
        InferenceCatalogPort.RuntimeInstallation runtime = catalog.runtime(draft.runtimeId())
                .orElseThrow(() -> new IllegalStateException("档案引用的运行时未安装"));
        requireSupported(runtime, draft.kind(), asset.modelType());
        throwIfCancelled(cancellation);
        var probe = runtimes.probeDraft(new InferenceRuntimePort.ProfileProbeCommand(
                UUID.randomUUID(), draft.name(), draft.kind(), draft.assetId(), draft.runtimeId(),
                draft.loadParameters(), draft.defaultParameters(), draft.contextLimit()), cancellation);
        throwIfCancelled(cancellation);
        if (draft.contextLimit() > probe.actualContextLength()) {
            throw new IllegalArgumentException("上下文限制超过模型实际能力："
                    + draft.contextLimit() + " > " + probe.actualContextLength());
        }
        if (draft.kind() == InferenceModelProfile.Kind.EMBEDDING
                && probe.actualEmbeddingDimensions() <= 0) {
            throw new IllegalStateException("运行时未返回模型实际嵌入维度");
        }
        Instant now = Instant.now();
        Instant createdAt = catalog.profile(draft.profileId())
                .map(InferenceModelProfile::createdAt).orElse(now);
        int contextLength = draft.contextLimit() == 0
                ? probe.actualContextLength() : draft.contextLimit();
        int dimensions = draft.kind() == InferenceModelProfile.Kind.EMBEDDING
                ? probe.actualEmbeddingDimensions() : 0;
        var verified = new InferenceModelProfile(draft.profileId(), draft.name(), draft.kind(),
                draft.assetId(), draft.runtimeId(), draft.loadParameters(), draft.defaultParameters(),
                contextLength, dimensions, InferenceModelProfile.State.READY, "", createdAt, now);
        runtimes.validateProfile(verified);
        // This is deliberately the only write: a failed/cancelled probe leaves the old READY row untouched.
        catalog.saveProfile(verified);
        return verified;
    }

    private InferenceModelAsset resolveLegacyModelType(
            InferenceModelAsset asset, BooleanSupplier cancelled) throws Exception {
        if (!"unknown".equals(asset.modelType())) return asset;
        String modelType = metadata.inspectModel(Path.of(asset.location()), cancelled).modelType();
        InferenceModelAsset updated = new InferenceModelAsset(
                asset.id(), asset.source(), asset.displayName(), modelType,
                asset.contentSha256(), asset.location(), asset.huggingFaceRepository(),
                asset.huggingFaceCommit(), asset.files(), asset.sizeBytes(), asset.state(),
                asset.failure(), asset.createdAt(), asset.artifactMetadata());
        catalog.saveAsset(updated);
        return updated;
    }

    private void requireSupportedByAnActiveRuntime(String modelType) {
        boolean supported = catalog.runtimes().stream()
                .filter(InferenceCatalogPort.RuntimeInstallation::active)
                .anyMatch(runtime -> runtimes.supportedModelTypes(
                                runtime.manifest(), InferenceModelProfile.Kind.GENERATION)
                        .contains(modelType)
                        || runtimes.supportedModelTypes(
                                runtime.manifest(), InferenceModelProfile.Kind.EMBEDDING)
                        .contains(modelType));
        if (!supported) {
            throw new IllegalArgumentException("当前活动推理运行时不支持模型类型 “"
                    + modelType + "”");
        }
    }

    private Set<String> supportedModelTypes() {
        java.util.LinkedHashSet<String> supported = new java.util.LinkedHashSet<>();
        catalog.runtimes().stream().filter(InferenceCatalogPort.RuntimeInstallation::active)
                .forEach(runtime -> {
                    supported.addAll(runtimes.supportedModelTypes(
                            runtime.manifest(), InferenceModelProfile.Kind.GENERATION));
                    supported.addAll(runtimes.supportedModelTypes(
                            runtime.manifest(), InferenceModelProfile.Kind.EMBEDDING));
                });
        if (supported.isEmpty()) {
            throw new IllegalStateException("没有活动的 Deliverance 推理运行时");
        }
        return Set.copyOf(supported);
    }

    private void requireSupported(
            InferenceCatalogPort.RuntimeInstallation runtime,
            InferenceModelProfile.Kind kind,
            String modelType) {
        if (!runtimes.supportedModelTypes(runtime.manifest(), kind).contains(modelType)) {
            throw new IllegalArgumentException("运行时 " + runtime.manifest().engineVersion()
                    + " 不支持将模型类型 “" + modelType + "”用于" +
                    (kind == InferenceModelProfile.Kind.EMBEDDING ? "嵌入" : "文本生成"));
        }
    }

    private static InferenceModelMetadataPort metadataFrom(InferenceAssetPreparationPort assets) {
        if (assets instanceof InferenceModelMetadataPort metadata) return metadata;
        return (source, cancelled) -> {
            throw new IllegalStateException("模型资产适配器未提供轻量 model_type 检测能力");
        };
    }

    @Override
    public void startProfile(UUID profileId) throws Exception {
        runtimes.start(requireReadyProfile(profileId));
    }

    @Override
    public void stopProfile(UUID profileId) { runtimes.stop(profileId); }

    @Override
    public java.util.List<String> recentLogs(UUID profileId, int maxLines) {
        return runtimes.recentLogs(profileId, maxLines);
    }

    @Override
    public void deleteProfile(UUID profileId) throws Exception {
        InferenceModelProfile profile = requireProfile(profileId);
        if (catalog.profileReferenced(profileId)) {
            throw new IllegalStateException("档案仍被工作区或公开模型引用，不能删除");
        }
        runtimes.stop(profileId);
        catalog.deleteProfile(profileId);
    }

    @Override
    public void deleteAsset(UUID assetId) throws Exception {
        InferenceModelAsset asset = catalog.asset(assetId)
                .orElseThrow(() -> new IllegalArgumentException("模型资产不存在"));
        if (catalog.assetReferenced(assetId)) throw new IllegalStateException("模型资产仍被档案引用，不能删除");
        // 先移除目录记录，避免文件删除成功而数据库失败后留下指向缺失目录的可加载记录。
        catalog.deleteAsset(assetId);
        assets.deleteManagedAsset(asset);
    }

    @Override
    public void bind(String workspaceId, InferenceCatalogPort.ModelTier tier, UUID profileId) {
        InferenceModelProfile profile = requireReadyProfile(profileId);
        if (tier == InferenceCatalogPort.ModelTier.EMBEDDING
                ^ profile.kind() == InferenceModelProfile.Kind.EMBEDDING) {
            throw new IllegalArgumentException("模型档案类型与工作区档位不匹配");
        }
        catalog.bind(requireText(workspaceId, "工作区 ID"), tier, profileId);
    }

    @Override
    public void saveBindings(
            String workspaceId, java.util.Map<InferenceCatalogPort.ModelTier, UUID> bindings) {
        String checkedWorkspace = requireText(workspaceId, "工作区 ID");
        java.util.Map<InferenceCatalogPort.ModelTier, UUID> checked = bindings == null
                ? java.util.Map.of() : java.util.Map.copyOf(bindings);
        checked.forEach((tier, profileId) -> {
            InferenceModelProfile profile = requireReadyProfile(profileId);
            if ((tier == InferenceCatalogPort.ModelTier.EMBEDDING)
                    != (profile.kind() == InferenceModelProfile.Kind.EMBEDDING)) {
                throw new IllegalArgumentException("模型档案类型与工作区档位不匹配: " + tier);
            }
        });
        catalog.replaceBindings(checkedWorkspace, checked);
    }

    @Override
    public void clearBinding(String workspaceId, InferenceCatalogPort.ModelTier tier) {
        catalog.clearBinding(requireText(workspaceId, "工作区 ID"), tier);
    }

    @Override
    public void publish(String alias, UUID profileId) {
        requireReadyProfile(profileId);
        Instant now = Instant.now();
        InferenceCatalogPort.PublishedModel previous = catalog.publishedModel(alias).orElse(null);
        catalog.publish(new InferenceCatalogPort.PublishedModel(alias, profileId, true,
                previous == null ? now : previous.createdAt(), now));
        try {
            apiServer.syncPublishedModels();
        } catch (Exception failure) {
            restorePublished(alias, previous, failure);
            throw new IllegalStateException("模型别名发布失败，已恢复原目录", failure);
        }
    }

    @Override
    public void unpublish(String alias) {
        InferenceCatalogPort.PublishedModel previous = catalog.publishedModel(alias).orElse(null);
        if (previous == null) return;
        catalog.unpublish(alias);
        try {
            apiServer.syncPublishedModels();
        } catch (Exception failure) {
            restorePublished(alias, previous, failure);
            throw new IllegalStateException("模型别名取消发布失败，已恢复原目录", failure);
        }
    }

    private void restorePublished(
            String alias, InferenceCatalogPort.PublishedModel previous, Exception original) {
        try {
            if (previous == null) catalog.unpublish(alias); else catalog.publish(previous);
            apiServer.syncPublishedModels();
        } catch (Exception rollbackFailure) {
            original.addSuppressed(rollbackFailure);
        }
    }

    @Override
    public void saveGateway(InferenceCatalogPort.GatewayConfiguration configuration) {
        saveAndReload(configuration);
    }

    @Override
    public void saveGateway(InferenceCatalogPort.GatewayConfiguration configuration,
                            char[] keyStorePassword) throws Exception {
        char[] supplied = keyStorePassword == null ? new char[0] : keyStorePassword;
        try {
            String encrypted;
            if (!configuration.tlsEnabled()) {
                encrypted = "";
            } else if (supplied.length > 0) {
                encrypted = secrets.encrypt(new String(supplied));
            } else {
                var current = catalog.gatewayConfiguration();
                encrypted = current.keyStorePath().equals(configuration.keyStorePath())
                        ? current.encryptedKeyStorePassword() : "";
            }
            var protectedConfiguration = new InferenceCatalogPort.GatewayConfiguration(
                    configuration.enabled(), configuration.bindAddress(), configuration.port(),
                    configuration.tlsEnabled(), configuration.allowInsecureLanWithoutTls(),
                    configuration.keyStorePath(), encrypted,
                    configuration.maxRequestBytes(), configuration.requestTimeoutSeconds(),
                    configuration.invocationLoggingEnabled(), Instant.now());
            saveAndReload(protectedConfiguration);
        } finally {
            java.util.Arrays.fill(supplied, '\0');
        }
    }

    @Override
    public String gatewayEndpoint() { return apiServer.endpoint(); }

    @Override
    public void setInvocationLogging(boolean enabled) throws Exception {
        if (!apiServer.supportsInvocationLogging()) {
            throw new IllegalStateException("当前 Deliverance 插件协议低于 1.2，请升级后再启用调用日志");
        }
        InferenceCatalogPort.GatewayConfiguration previous = catalog.gatewayConfiguration();
        if (previous.invocationLoggingEnabled() == enabled) return;
        InferenceCatalogPort.GatewayConfiguration changed =
                new InferenceCatalogPort.GatewayConfiguration(
                        previous.enabled(), previous.bindAddress(), previous.port(),
                        previous.tlsEnabled(), previous.allowInsecureLanWithoutTls(),
                        previous.keyStorePath(), previous.encryptedKeyStorePassword(),
                        previous.maxRequestBytes(), previous.requestTimeoutSeconds(), enabled,
                        Instant.now());
        catalog.saveGatewayConfiguration(changed);
        try {
            apiServer.setInvocationLogging(enabled);
        } catch (Exception failure) {
            catalog.saveGatewayConfiguration(previous);
            try {
                apiServer.setInvocationLogging(previous.invocationLoggingEnabled());
            } catch (Exception rollbackFailure) {
                failure.addSuppressed(rollbackFailure);
            }
            throw new IllegalStateException("调用日志开关应用失败，已恢复原设置", failure);
        }
    }

    @Override
    public boolean invocationLoggingSupported() {
        return apiServer.supportsInvocationLogging();
    }

    @Override
    public InferenceApiServerControlPort.ServiceSnapshot modelServiceSnapshot() {
        return apiServer.serviceSnapshot();
    }

    private void saveGatewayRecord(InferenceCatalogPort.GatewayConfiguration configuration) {
        if (configuration.enabled()) {
            if (catalog.apiKeys().stream().noneMatch(key -> !key.revoked())) {
                throw new IllegalStateException("启用 API 服务前必须至少创建一个有效密钥");
            }
            if (!configuration.loopbackOnly()
                    && !configuration.tlsEnabled()
                    && !configuration.allowInsecureLanWithoutTls()) {
                throw new IllegalArgumentException("局域网 HTTP 监听必须先确认未加密风险");
            }
            if (!configuration.loopbackOnly() && configuration.tlsEnabled()
                    && configuration.keyStorePath().isBlank()) {
                throw new IllegalArgumentException("局域网 TLS 监听必须配置 PKCS#12 证书");
            }
        }
        catalog.saveGatewayConfiguration(configuration);
    }

    private void saveAndReload(InferenceCatalogPort.GatewayConfiguration configuration) {
        InferenceCatalogPort.GatewayConfiguration previous = catalog.gatewayConfiguration();
        saveGatewayRecord(configuration);
        try {
            apiServer.reconfigure();
        } catch (Exception failure) {
            catalog.saveGatewayConfiguration(previous);
            try {
                apiServer.reconfigure();
            } catch (Exception rollbackFailure) {
                failure.addSuppressed(rollbackFailure);
            }
            throw new IllegalStateException("API 服务监听器重载失败，已恢复原配置", failure);
        }
    }

    @Override
    public CreatedApiKey createApiKey(String name, Set<InferenceCatalogPort.ApiScope> scopes,
                                      Set<String> aliases, int rpm, long tpm, int concurrency) {
        byte[] secretBytes = new byte[32];
        byte[] saltBytes = new byte[16];
        RANDOM.nextBytes(secretBytes);
        RANDOM.nextBytes(saltBytes);
        String raw = "jcl_" + Base64.getUrlEncoder().withoutPadding().encodeToString(secretBytes);
        String prefix = raw.substring(0, Math.min(12, raw.length()));
        String salt = Base64.getUrlEncoder().withoutPadding().encodeToString(saltBytes);
        String digest = derive(raw, saltBytes);
        var record = new InferenceCatalogPort.ApiKeyRecord(UUID.randomUUID(), name, prefix, salt,
                digest, scopes, aliases, rpm, tpm, concurrency, false, Instant.now(), null);
        catalog.saveApiKey(record);
        if (catalog.gatewayConfiguration().enabled()) {
            try {
                apiServer.reconfigure();
            } catch (Exception failure) {
                catalog.revokeApiKey(record.id());
                try { apiServer.reconfigure(); }
                catch (Exception rollbackFailure) { failure.addSuppressed(rollbackFailure); }
                throw new IllegalStateException("API Key 生效失败，已撤销新密钥", failure);
            }
        }
        return new CreatedApiKey(record, raw);
    }

    @Override
    public void revokeApiKey(UUID id) {
        var selected = catalog.apiKeys().stream().filter(key -> key.id().equals(id)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("API 密钥不存在"));
        if (!selected.revoked() && catalog.gatewayConfiguration().enabled()) {
            long remaining = catalog.apiKeys().stream().filter(key -> !key.revoked()
                    && !key.id().equals(id)).count();
            if (remaining == 0) throw new IllegalStateException("API 服务启用期间必须保留至少一个有效密钥");
        }
        catalog.revokeApiKey(id);
        if (catalog.gatewayConfiguration().enabled()) {
            try {
                apiServer.reconfigure();
            } catch (Exception failure) {
                catalog.saveApiKey(selected);
                try { apiServer.reconfigure(); }
                catch (Exception rollbackFailure) { failure.addSuppressed(rollbackFailure); }
                throw new IllegalStateException("API Key 撤销生效失败，已恢复原密钥", failure);
            }
        }
    }

    private InferenceModelProfile requireProfile(UUID id) {
        return catalog.profile(id).orElseThrow(() -> new IllegalArgumentException("模型档案不存在"));
    }

    private InferenceModelProfile requireReadyProfile(UUID id) {
        InferenceModelProfile profile = requireProfile(id);
        if (profile.state() != InferenceModelProfile.State.READY) {
            throw new IllegalStateException("模型档案尚未通过实际加载探测");
        }
        return profile;
    }

    private static String derive(String raw, byte[] salt) {
        try {
            PBEKeySpec spec = new PBEKeySpec(raw.toCharArray(), salt, 120_000, 256);
            try {
                return Base64.getUrlEncoder().withoutPadding().encodeToString(
                        SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded());
            } finally {
                spec.clearPassword();
            }
        } catch (Exception failure) {
            throw new IllegalStateException("无法创建 API 密钥摘要", failure);
        }
    }

    private static Consumer<InferenceAssetPreparationPort.Progress> safe(
            Consumer<InferenceAssetPreparationPort.Progress> value) {
        return value == null ? ignored -> { } : value;
    }
    private static BooleanSupplier safe(BooleanSupplier value) { return value == null ? () -> false : value; }
    private static String requireText(String value, String label) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(label + "不能为空");
        return value.strip();
    }
    private static String safeMessage(Throwable failure) {
        return failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
    }

    private static void throwIfCancelled(BooleanSupplier cancelled) {
        if (Thread.currentThread().isInterrupted() || cancelled.getAsBoolean()) {
            throw new CancellationException("模型档案探测已取消");
        }
    }
}
