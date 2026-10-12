package com.javaclaw.application.inference;

import com.javaclaw.inference.api.InferenceModelAsset;
import com.javaclaw.inference.api.InferenceModelProfile;

import java.nio.file.Path;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** 固定模型页面的提供者身份，并防止局部工作区选择覆盖其他插件的绑定。 */
public final class PluginScopedInferenceManagement implements InferenceManagementApplicationService {
    private final InferenceManagementApplicationService delegate;
    private final String workspace, plugin;
    public PluginScopedInferenceManagement(InferenceManagementApplicationService delegate, String workspace, String plugin) {
        this.delegate = java.util.Objects.requireNonNull(delegate);
        this.workspace = java.util.Objects.requireNonNull(workspace); this.plugin = java.util.Objects.requireNonNull(plugin);
    }
    @Override public Snapshot snapshot(String workspaceId) { requireWorkspace(workspaceId); return filter(delegate.snapshot(workspace)); }
    @Override public Snapshot snapshot(String workspaceId, Projection projection) {
        requireWorkspace(workspaceId); return filter(delegate.snapshot(workspace, projection));
    }
    private Snapshot filter(Snapshot all) {
        var runtimes = all.runtimes().stream().filter(value -> plugin.equals(value.manifest().runtimeId())).toList();
        var profiles = all.profiles().stream().filter(this::owned).toList();
        Set<UUID> referenced = profiles.stream().map(InferenceModelProfile::assetId).collect(java.util.stream.Collectors.toSet());
        Set<String> types = runtimes.stream().flatMap(runtime -> java.util.stream.Stream.of(InferenceModelProfile.Kind.values())
                .flatMap(kind -> runtime.manifest().supportedModelTypes(kind).stream())).collect(java.util.stream.Collectors.toSet());
        var assets = all.assets().stream().filter(asset -> referenced.contains(asset.id()) || types.contains(asset.modelType())).toList();
        Set<UUID> ids = profiles.stream().map(InferenceModelProfile::id).collect(java.util.stream.Collectors.toSet());
        return new Snapshot(runtimes, assets, profiles, all.bindings(), all.publishedModels().stream()
                .filter(value -> ids.contains(value.profileId())).toList(), all.gateway(), all.apiKeys());
    }
    private boolean owned(InferenceModelProfile profile) { return plugin.equals(profile.runtimeId()); }
    private void requireProfile(UUID id) {
        if (delegate.snapshot(workspace, Projection.PROFILE_CONFIGURATION).profiles().stream().noneMatch(value -> value.id().equals(id) && owned(value))) {
            throw new IllegalArgumentException("模型档案不属于当前插件");
        }
    }
    private void requireWorkspace(String id) { if (!workspace.equals(id)) throw new IllegalArgumentException("模型页面不能跨工作区操作"); }
    @Override public List<InferenceModelProfile> readyProfiles(InferenceModelProfile.Kind kind) {
        return delegate.readyProfiles(kind).stream().filter(this::owned).toList();
    }
    @Override public Map<UUID, InferenceRuntimePort.RuntimeProfileStatus> runtimeStatuses(Set<UUID> ids) {
        Set<UUID> ownedIds = delegate.snapshot(workspace, Projection.PROFILE_CONFIGURATION).profiles().stream()
                .filter(this::owned).map(InferenceModelProfile::id).collect(java.util.stream.Collectors.toSet());
        if (!ownedIds.containsAll(ids)) throw new IllegalArgumentException("模型档案不属于当前插件");
        return delegate.runtimeStatuses(ids);
    }
    @Override public InferenceModelAsset importLocal(Path source, Consumer<InferenceAssetPreparationPort.Progress> progress,
                                                    BooleanSupplier cancelled) throws Exception { return delegate.importLocal(plugin, source, progress, cancelled); }
    @Override public InferenceAssetPreparationPort.HuggingFacePreview previewHuggingFace(
            InferenceAssetPreparationPort.HuggingFaceRequest request, BooleanSupplier cancelled) throws Exception {
        return delegate.previewHuggingFace(request, cancelled);
    }
    @Override public InferenceModelAsset downloadHuggingFace(InferenceAssetPreparationPort.HuggingFaceRequest request,
            Consumer<InferenceAssetPreparationPort.Progress> progress, BooleanSupplier cancelled) throws Exception {
        return delegate.downloadHuggingFace(request, progress, cancelled);
    }
    @Override public HuggingFaceModelCatalogPort.SearchPage searchOnlineModels(HuggingFaceModelCatalogPort.SearchRequest request,
            Consumer<HuggingFaceModelCatalogPort.SearchProgress> progress, BooleanSupplier cancelled) throws Exception {
        return delegate.searchOnlineModels(new HuggingFaceModelCatalogPort.SearchRequest(request.query(), request.cursor(), request.limit(), plugin), progress, cancelled);
    }
    @Override public HuggingFaceModelCatalogPort.ModelDetail onlineModelDetail(String repository, BooleanSupplier cancelled) throws Exception {
        return delegate.onlineModelDetail(plugin, repository, cancelled);
    }
    @Override public InferenceModelAsset downloadOnlineModel(HuggingFaceModelCatalogPort.ModelDetail model,
            Consumer<InferenceAssetPreparationPort.Progress> progress, BooleanSupplier cancelled) throws Exception {
        if (!model.pluginId().isBlank() && !plugin.equals(model.pluginId())) throw new IllegalArgumentException("模型详情提供者不匹配");
        return delegate.downloadOnlineModel(new HuggingFaceModelCatalogPort.ModelDetail(model.summary(), model.license(),
                model.architectures(), model.fileCount(), model.declaredContextLength(), model.url(), plugin), progress, cancelled);
    }
    @Override public RecommendedProfile recommendedProfile(UUID asset, InferenceModelProfile.Kind kind, BooleanSupplier cancelled) throws Exception {
        RecommendedProfile result = delegate.recommendedProfile(plugin, asset, kind, cancelled);
        if (!plugin.equals(result.runtimeId())) throw new IllegalStateException("推荐配置的模型提供者不匹配");
        return result;
    }
    @Override public InferenceModelProfile saveAndVerifyProfile(ProfileDraft draft, BooleanSupplier cancelled) throws Exception {
        if (!plugin.equals(draft.runtimeId())) throw new IllegalArgumentException("运行时必须绑定当前插件");
        var existing = delegate.snapshot(workspace, Projection.PROFILE_CONFIGURATION).profiles().stream()
                .filter(value -> value.id().equals(draft.profileId())).findFirst();
        if (existing.isPresent() && !owned(existing.get())) throw new IllegalArgumentException("不能修改其他插件的模型档案");
        return delegate.saveAndVerifyProfile(draft, cancelled);
    }
    @Override public void startProfile(UUID id) throws Exception { requireProfile(id); delegate.startProfile(id); }
    @Override public void stopProfile(UUID id) { requireProfile(id); delegate.stopProfile(id); }
    @Override public List<String> recentLogs(UUID id, int maxLines) { requireProfile(id); return delegate.recentLogs(id, maxLines); }
    @Override public void deleteProfile(UUID id) throws Exception { requireProfile(id); delegate.deleteProfile(id); }
    @Override public void deleteAsset(UUID id) throws Exception {
        if (snapshot(workspace).assets().stream().noneMatch(value -> value.id().equals(id))) throw new IllegalArgumentException("模型文件不属于当前插件可管理的共享资产");
        delegate.deleteAsset(id);
    }
    @Override public void saveBindings(String workspaceId, Map<InferenceCatalogPort.ModelTier, UUID> bindings) {
        requireWorkspace(workspaceId);
        var all = delegate.snapshot(workspace, Projection.PROFILE_CONFIGURATION);
        Set<UUID> ownedIds = all.profiles().stream().filter(this::owned).map(InferenceModelProfile::id).collect(java.util.stream.Collectors.toSet());
        EnumMap<InferenceCatalogPort.ModelTier, UUID> merged = new EnumMap<>(InferenceCatalogPort.ModelTier.class);
        all.bindings().forEach((tier, id) -> { if (!ownedIds.contains(id)) merged.put(tier, id); });
        bindings.forEach((tier, id) -> {
            if (ownedIds.contains(id) || id.equals(all.bindings().get(tier))) merged.put(tier, id);
            else throw new IllegalArgumentException("不能指定其他插件的模型档案");
        });
        delegate.saveBindings(workspace, merged);
    }
    @Override public void bind(String workspaceId, InferenceCatalogPort.ModelTier tier, UUID id) {
        requireWorkspace(workspaceId); requireProfile(id); delegate.bind(workspace, tier, id);
    }
    @Override public void clearBinding(String workspaceId, InferenceCatalogPort.ModelTier tier) {
        requireWorkspace(workspaceId);
        UUID existing = delegate.snapshot(workspace, Projection.PROFILE_CONFIGURATION).bindings().get(tier);
        if (existing != null) { requireProfile(existing); delegate.clearBinding(workspace, tier); }
    }
    @Override public void publish(String alias, UUID id) { requireProfile(id); requireAlias(alias); delegate.publish(alias, id); }
    private void requireAlias(String alias) {
        delegate.snapshot(workspace, Projection.API_CONFIGURATION).publishedModels().stream()
                .filter(value -> value.alias().equals(alias)).findFirst().ifPresent(value -> requireProfile(value.profileId()));
    }
    @Override public void unpublish(String alias) {
        requireAlias(alias); delegate.unpublish(alias);
    }
    @Override public void saveGateway(InferenceCatalogPort.GatewayConfiguration configuration) { delegate.saveGateway(configuration.withProvider(plugin)); }
    @Override public void saveGateway(InferenceCatalogPort.GatewayConfiguration configuration, char[] password) throws Exception {
        delegate.saveGateway(configuration.withProvider(plugin), password);
    }
    @Override public void setInvocationLogging(boolean enabled) throws Exception {
        if (!plugin.equals(delegate.snapshot(workspace, Projection.API_CONFIGURATION).gateway().providerId())) {
            throw new IllegalStateException("当前插件不是公开接口提供者，请先明确接管接口");
        }
        delegate.setInvocationLogging(enabled);
    }
    @Override public boolean invocationLoggingSupported() { return delegate.invocationLoggingSupported(); }
    @Override public InferenceApiServerControlPort.ServiceSnapshot modelServiceSnapshot() { return delegate.modelServiceSnapshot(); }
    @Override public String gatewayEndpoint() { return delegate.gatewayEndpoint(); }
    @Override public CreatedApiKey createApiKey(String name, Set<InferenceCatalogPort.ApiScope> scopes, Set<String> aliases,
                                               int rpm, long tpm, int concurrency) { return delegate.createApiKey(name, scopes, aliases, rpm, tpm, concurrency); }
    @Override public void revokeApiKey(UUID id) { delegate.revokeApiKey(id); }
}
