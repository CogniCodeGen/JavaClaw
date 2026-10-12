package com.javaclaw.infrastructure.inference;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.javaclaw.application.inference.*;
import com.javaclaw.application.plugin.v2.PluginManagementService;
import com.javaclaw.config.WorkspaceManager;
import com.javaclaw.inference.api.InferenceModelAsset;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** 目录与参数推荐由已启用的模型插件提供，宿主不解释模型格式或引擎参数。 */
public final class PluginModelCatalogAdapter implements HuggingFaceModelCatalogPort, InferenceSystemProfilePort, InferenceModelMetadataPort {
    private final PluginManagementService plugins;
    private final WorkspaceManager workspaces;
    private final InferenceCatalogPort catalog;
    private final ObjectMapper json;

    public PluginModelCatalogAdapter(PluginManagementService plugins, WorkspaceManager workspaces,
                                     InferenceCatalogPort catalog, ObjectMapper json) {
        this.plugins = plugins; this.workspaces = workspaces; this.catalog = catalog; this.json = json;
    }

    @Override public SearchPage search(SearchRequest request, Set<String> supportedModelTypes,
                                       BooleanSupplier cancelled) throws Exception {
        return invoke(request.pluginId(), "catalog.search", Map.of("query", request.query(), "cursor", request.cursor(),
                "limit", request.limit()), SearchPage.class, cancelled);
    }

    @Override public SearchPage searchIncrementally(SearchRequest request, Set<String> supportedModelTypes,
                                                     Consumer<SearchProgress> progress,
                                                     BooleanSupplier cancelled) throws Exception {
        if (progress != null) progress.accept(new SearchProgress(SearchState.LOADING, List.of(), 0, 0,
                Instant.EPOCH, "", true, Map.of()));
        SearchPage page = search(request, supportedModelTypes, cancelled);
        if (progress != null) progress.accept(new SearchProgress(SearchState.READY, page.models(),
                page.models().size(), page.models().size(), Instant.now(), "", true, Map.of()));
        return page;
    }

    @Override public ModelDetail detail(String repository, Set<String> supportedModelTypes,
                                         BooleanSupplier cancelled) throws Exception {
        return detail("", repository, supportedModelTypes, cancelled);
    }
    @Override public ModelDetail detail(String pluginId, String repository, Set<String> supported, BooleanSupplier cancelled) throws Exception {
        String selected = select(pluginId);
        ModelDetail result = invoke(selected, "catalog.detail", Map.of("repository", repository), ModelDetail.class, cancelled);
        return new ModelDetail(result.summary(), result.license(), result.architectures(), result.fileCount(),
                result.declaredContextLength(), result.url(), selected);
    }

    @Override public Recommendation recommend(InferenceModelMetadataPort.ModelMetadata model,
                                               InferenceModelAsset.ArtifactMetadata artifact) {
        return recommend("", model, artifact);
    }
    @Override public Recommendation recommend(String pluginId, InferenceModelMetadataPort.ModelMetadata model, InferenceModelAsset.ArtifactMetadata artifact) {
        try {
            var payload = json.createObjectNode();
            payload.set("model", json.valueToTree(model)); payload.set("artifact", json.valueToTree(artifact));
            return invoke(pluginId, "recommend", payload, Recommendation.class, () -> false);
        } catch (Exception failure) {
            throw new IllegalStateException("模型插件无法推荐配置", failure);
        }
    }

    @Override public InferenceModelMetadataPort.ModelMetadata inspectModel(java.nio.file.Path path, BooleanSupplier cancelled) throws Exception {
        return inspectModel("", path, cancelled);
    }
    @Override public InferenceModelMetadataPort.ModelMetadata inspectModel(String pluginId, java.nio.file.Path path, BooleanSupplier cancelled) throws Exception {
        return invoke(pluginId, "model.inspect", Map.of("modelPath", path.toString()), InferenceModelMetadataPort.ModelMetadata.class, cancelled);
    }
    @Override public InferenceModelAsset.ArtifactMetadata inspectArtifact(String pluginId, java.nio.file.Path path, long bytes, BooleanSupplier cancelled) throws Exception {
        return invoke(pluginId, "asset.inspect", Map.of("modelPath", path.toString(), "quantizedSizeBytes", bytes), InferenceModelAsset.ArtifactMetadata.class, cancelled);
    }
    private String select(String pluginId) {
        String workspace = workspaces.getCurrentWorkspaceId();
        var candidates = plugins.list(workspace).stream().filter(value -> value.enabled()
                && value.capabilities().contains("MODEL")).toList();
        if (pluginId != null && !pluginId.isBlank()) {
            return candidates.stream().filter(value -> value.id().equals(pluginId)).findFirst()
                    .orElseThrow(() -> new IllegalStateException("模型插件未启用: " + pluginId)).id();
        }
        var activeIds = catalog.runtimes().stream().filter(InferenceCatalogPort.RuntimeInstallation::active)
                .map(value -> value.manifest().runtimeId()).toList();
        var active = candidates.stream().filter(value -> activeIds.contains(value.id())).toList();
        var selected = active.size() == 1 ? active : candidates;
        if (selected.size() != 1) throw new IllegalStateException("请在插件中心选择一个模型提供者");
        return selected.getFirst().id();
    }
    private <T> T invoke(String pluginId, String operation, Object payload, Class<T> type, BooleanSupplier cancelled) throws Exception {
        String workspace = workspaces.getCurrentWorkspaceId();
        String selected = select(pluginId);
        try (var lease = plugins.acquireRun(workspace, selected)) {
            String contribution = RemoteInferenceRuntimeCatalog.modelContribution(lease.contributions()).id();
            var call = plugins.invoke(lease, "MODEL", contribution, operation,
                    json.writeValueAsString(payload), Duration.ofMinutes(2), ignored -> { });
            try {
                while (true) {
                    if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) {
                        throw new CancellationException("模型目录操作已取消");
                    }
                    try {
                        return json.readValue(call.completion().get(100, TimeUnit.MILLISECONDS).payloadJson(), type);
                    } catch (TimeoutException waiting) { /* 轮询取消状态，RPC 本身保留截止时间。 */ }
                }
            } finally { if (!call.completion().isDone()) call.cancel(); }
        }
    }
}
