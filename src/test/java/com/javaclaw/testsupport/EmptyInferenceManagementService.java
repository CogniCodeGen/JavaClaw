package com.javaclaw.testsupport;

import com.javaclaw.application.inference.InferenceAssetPreparationPort;
import com.javaclaw.application.inference.InferenceCatalogPort;
import com.javaclaw.application.inference.InferenceManagementApplicationService;
import com.javaclaw.inference.api.InferenceModelAsset;
import com.javaclaw.inference.api.InferenceModelProfile;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** 为只验证 UI 装配的测试提供无外部副作用的本地推理服务。 */
public class EmptyInferenceManagementService implements InferenceManagementApplicationService {
    private final Snapshot snapshot = new Snapshot(List.of(), List.of(), List.of(), Map.of(),
            List.of(), InferenceCatalogPort.GatewayConfiguration.defaults(), List.of());

    @Override public Snapshot snapshot(String workspaceId) { return snapshot; }
    @Override public String gatewayEndpoint() { return ""; }
    @Override public InferenceModelAsset importLocal(Path source,
            Consumer<InferenceAssetPreparationPort.Progress> progress,
            BooleanSupplier cancelled) { throw unsupported(); }
    @Override public InferenceAssetPreparationPort.HuggingFacePreview previewHuggingFace(
            InferenceAssetPreparationPort.HuggingFaceRequest request,
            BooleanSupplier cancelled) { throw unsupported(); }
    @Override public InferenceModelAsset downloadHuggingFace(
            InferenceAssetPreparationPort.HuggingFaceRequest request,
            Consumer<InferenceAssetPreparationPort.Progress> progress,
            BooleanSupplier cancelled) { throw unsupported(); }
    @Override public InferenceModelProfile saveAndVerifyProfile(
            ProfileDraft draft, BooleanSupplier cancelled) { throw unsupported(); }
    @Override public void startProfile(UUID profileId) { throw unsupported(); }
    @Override public void stopProfile(UUID profileId) { }
    @Override public List<String> recentLogs(UUID profileId, int maxLines) { return List.of(); }
    @Override public void deleteProfile(UUID profileId) { throw unsupported(); }
    @Override public void deleteAsset(UUID assetId) { throw unsupported(); }
    @Override public void saveBindings(String workspaceId,
            Map<InferenceCatalogPort.ModelTier, UUID> bindings) { throw unsupported(); }
    @Override public void bind(String workspaceId, InferenceCatalogPort.ModelTier tier,
            UUID profileId) { throw unsupported(); }
    @Override public void clearBinding(String workspaceId,
            InferenceCatalogPort.ModelTier tier) { throw unsupported(); }
    @Override public void publish(String alias, UUID profileId) { throw unsupported(); }
    @Override public void unpublish(String alias) { throw unsupported(); }
    @Override public void saveGateway(
            InferenceCatalogPort.GatewayConfiguration configuration) { throw unsupported(); }
    @Override public void saveGateway(InferenceCatalogPort.GatewayConfiguration configuration,
            char[] keyStorePassword) { throw unsupported(); }
    @Override public CreatedApiKey createApiKey(String name,
            Set<InferenceCatalogPort.ApiScope> scopes, Set<String> aliases,
            int rpm, long tpm, int concurrency) { throw unsupported(); }
    @Override public void revokeApiKey(UUID id) { throw unsupported(); }

    private static UnsupportedOperationException unsupported() {
        return new UnsupportedOperationException("UI load test");
    }
}
