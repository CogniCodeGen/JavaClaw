package com.javaclaw.application.inference;

import com.javaclaw.inference.api.InferenceModelAsset;
import com.javaclaw.inference.api.InferenceModelProfile;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** “本地推理”设置页及首启流程使用的应用入口。 */
public interface InferenceManagementApplicationService {

    Snapshot snapshot(String workspaceId);

    /** 高级管理页按职责读取数据。 */
    Snapshot snapshot(String workspaceId, Projection projection);

    /** 模型配置页使用的轻量投影；不读取运行时、资产、绑定、密钥或网关配置。 */
    List<InferenceModelProfile> readyProfiles(InferenceModelProfile.Kind kind);

    /** Returns non-sensitive residency state for the requested profiles. */
    Map<UUID, InferenceRuntimePort.RuntimeProfileStatus> runtimeStatuses(Set<UUID> profileIds);

    InferenceModelAsset importLocal(Path source, Consumer<InferenceAssetPreparationPort.Progress> progress,
                                    BooleanSupplier cancelled) throws Exception;
    InferenceAssetPreparationPort.HuggingFacePreview previewHuggingFace(
            InferenceAssetPreparationPort.HuggingFaceRequest request,
            BooleanSupplier cancelled) throws Exception;
    InferenceModelAsset downloadHuggingFace(InferenceAssetPreparationPort.HuggingFaceRequest request,
                                            Consumer<InferenceAssetPreparationPort.Progress> progress,
                                            BooleanSupplier cancelled) throws Exception;

    HuggingFaceModelCatalogPort.SearchPage searchOnlineModels(
            HuggingFaceModelCatalogPort.SearchRequest request,
            Consumer<HuggingFaceModelCatalogPort.SearchProgress> progress,
            BooleanSupplier cancelled) throws Exception;

    HuggingFaceModelCatalogPort.ModelDetail onlineModelDetail(
            String repository, BooleanSupplier cancelled) throws Exception;

    InferenceModelAsset downloadOnlineModel(
            HuggingFaceModelCatalogPort.ModelDetail model,
            Consumer<InferenceAssetPreparationPort.Progress> progress,
            BooleanSupplier cancelled) throws Exception;

    RecommendedProfile recommendedProfile(
            UUID assetId, InferenceModelProfile.Kind kind,
            BooleanSupplier cancelled) throws Exception;
    InferenceModelProfile saveAndVerifyProfile(ProfileDraft draft, BooleanSupplier cancelled) throws Exception;
    void startProfile(UUID profileId) throws Exception;
    void stopProfile(UUID profileId);

    /**
     * Changes residency and verifies the observable runtime state before reporting success. UI and
     * automation callers share this boundary so a completed command cannot be rendered from stale
     * assumptions.
     */
    default ProfileRuntimeTransition setProfileRunning(UUID profileId, boolean running)
            throws Exception {
        Objects.requireNonNull(profileId, "profileId");
        if (running) startProfile(profileId); else stopProfile(profileId);
        InferenceRuntimePort.RuntimeProfileStatus status = runtimeStatuses(Set.of(profileId))
                .get(profileId);
        boolean confirmed = status != null && status.running();
        if (confirmed != running) {
            throw new IllegalStateException(running
                    ? "模型加载已完成，但运行时未确认驻留状态"
                    : "模型卸载已完成，但运行时仍报告驻留状态");
        }
        return new ProfileRuntimeTransition(profileId, confirmed, status);
    }

    List<String> recentLogs(UUID profileId, int maxLines);
    void deleteProfile(UUID profileId) throws Exception;
    void deleteAsset(UUID assetId) throws Exception;
    void saveBindings(String workspaceId, Map<InferenceCatalogPort.ModelTier, UUID> bindings);
    void bind(String workspaceId, InferenceCatalogPort.ModelTier tier, UUID profileId);
    void clearBinding(String workspaceId, InferenceCatalogPort.ModelTier tier);
    void publish(String alias, UUID profileId);
    void unpublish(String alias);
    void saveGateway(InferenceCatalogPort.GatewayConfiguration configuration);
    void saveGateway(InferenceCatalogPort.GatewayConfiguration configuration,
                     char[] keyStorePassword) throws Exception;
    void setInvocationLogging(boolean enabled) throws Exception;
    boolean invocationLoggingSupported();
    InferenceApiServerControlPort.ServiceSnapshot modelServiceSnapshot();
    String gatewayEndpoint();
    CreatedApiKey createApiKey(String name, Set<InferenceCatalogPort.ApiScope> scopes,
                               Set<String> aliases, int rpm, long tpm, int concurrency);
    void revokeApiKey(UUID id);

    record Snapshot(
            List<InferenceCatalogPort.RuntimeInstallation> runtimes,
            List<InferenceModelAsset> assets,
            List<InferenceModelProfile> profiles,
            Map<InferenceCatalogPort.ModelTier, UUID> bindings,
            List<InferenceCatalogPort.PublishedModel> publishedModels,
            InferenceCatalogPort.GatewayConfiguration gateway,
            List<InferenceCatalogPort.ApiKeyRecord> apiKeys) {
        public Snapshot {
            runtimes = List.copyOf(runtimes);
            assets = List.copyOf(assets);
            profiles = List.copyOf(profiles);
            bindings = Map.copyOf(bindings);
            publishedModels = List.copyOf(publishedModels);
            apiKeys = List.copyOf(apiKeys);
        }
    }

    /** Editable input. A zero context limit means “use the model's probed maximum”. */
    record ProfileDraft(
            UUID profileId,
            String name,
            InferenceModelProfile.Kind kind,
            UUID assetId,
            String runtimeId,
            Map<String, Object> loadParameters,
            Map<String, Object> defaultParameters,
            int contextLimit) {
        public ProfileDraft {
            if (profileId == null) throw new IllegalArgumentException("档案 ID 不能为空");
            if (name == null || name.isBlank()) throw new IllegalArgumentException("档案名称不能为空");
            if (kind == null) throw new IllegalArgumentException("模型类型不能为空");
            if (assetId == null) throw new IllegalArgumentException("模型资产不能为空");
            if (runtimeId == null || runtimeId.isBlank()) throw new IllegalArgumentException("运行时不能为空");
            loadParameters = loadParameters == null ? Map.of() : Map.copyOf(loadParameters);
            defaultParameters = defaultParameters == null ? Map.of() : Map.copyOf(defaultParameters);
            if (contextLimit < 0) throw new IllegalArgumentException("上下文限制不能为负数");
        }
    }

    /** secret 只在创建调用的返回值中出现一次，永不从仓储读回。 */
    record CreatedApiKey(InferenceCatalogPort.ApiKeyRecord metadata, String secret) { }

    record ProfileRuntimeTransition(
            UUID profileId, boolean running,
            InferenceRuntimePort.RuntimeProfileStatus status) {
        public ProfileRuntimeTransition {
            Objects.requireNonNull(profileId, "profileId");
            if (running && (status == null || !status.running())) {
                throw new IllegalArgumentException("驻留转换缺少运行时确认");
            }
        }
    }

    record RecommendedProfile(
            String runtimeId,
            int contextLength,
            Map<String, Object> loadParameters,
            Map<String, Object> defaultParameters,
            InferenceSystemProfilePort.SystemCapacity capacity,
            InferenceSystemProfilePort.MemoryAssessment memory) {
        public RecommendedProfile {
            if (runtimeId == null || runtimeId.isBlank()) {
                throw new IllegalArgumentException("推荐配置缺少运行时");
            }
            if (contextLength < 0) throw new IllegalArgumentException("推荐上下文无效");
            loadParameters = loadParameters == null ? Map.of() : Map.copyOf(loadParameters);
            defaultParameters = defaultParameters == null ? Map.of() : Map.copyOf(defaultParameters);
            if (capacity == null || memory == null) {
                throw new IllegalArgumentException("推荐配置缺少系统容量信息");
            }
        }
    }

    enum Projection {
        /** Models page: runtime capabilities, managed model files, run settings and workspace choices. */
        MODEL_MANAGEMENT,
        PROFILE_CONFIGURATION,
        ASSET_CATALOG,
        RUNTIME_CATALOG,
        API_CONFIGURATION,
        SERVICE_CONSOLE
    }
}
