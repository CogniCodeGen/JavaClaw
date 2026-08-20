package com.javaclaw.ui.javafx.settings;

import com.javaclaw.application.inference.HuggingFaceModelCatalogPort;
import com.javaclaw.application.inference.InferenceCatalogPort;
import com.javaclaw.application.inference.InferenceManagementApplicationService;
import com.javaclaw.application.inference.InferenceRuntimePort;
import com.javaclaw.application.inference.InferenceSystemProfilePort;
import com.javaclaw.application.inference.LocalInferenceQuickSetupApplicationService.SupportedModelType;
import com.javaclaw.inference.api.InferenceModelAsset;
import com.javaclaw.inference.api.InferenceModelProfile;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Pure presentation formatting shared by the three Deliverance settings controllers. */
final class InferenceModelPresentation {
    private InferenceModelPresentation() { }

    static String onlineChoice(HuggingFaceModelCatalogPort.ModelSummary model) {
        return model.repository() + " · " + model.quantizationType() + " · "
                + bytes(model.quantizedSizeBytes()) + " · " + instant(model.lastModified())
                + (model.gated() ? " · gated" : "");
    }

    static String onlineDetail(HuggingFaceModelCatalogPort.ModelDetail detail,
                               Set<InferenceModelProfile.Kind> supported) {
        var model = detail.summary();
        return "仓库：" + model.repository() + "\n固定 commit：" + model.commit()
                + "\n许可：" + detail.license() + "\n模型类型：" + model.modelType()
                + " · " + SupportedModelType.fromId(model.modelType()).displayName()
                + "\n支持用途：" + InferenceModelPurposeClassifier.purposeLabel(supported)
                + "\n架构：" + (detail.architectures().isEmpty() ? "未声明"
                        : String.join(", ", detail.architectures()))
                + "\n上下文上限：" + (detail.declaredContextLength() <= 0
                        ? "自动探测" : detail.declaredContextLength())
                + "\n文件数：" + detail.fileCount()
                + "\n原始大小：" + bytes(model.sourceSizeBytes())
                + "\n量化大小：" + bytes(model.quantizedSizeBytes())
                + " (" + model.quantizationType() + ")"
                + "\n来源更新时间：" + instant(model.lastModified())
                + (model.gated() ? "\n\n该仓库受许可门控；匿名目录仅展示信息，不能下载。" : "");
    }

    static String localChoice(InferenceModelAsset model, boolean loaded) {
        long size = quantizedBytes(model);
        return (loaded ? "READY  " : "LOCAL  ") + model.displayName() + " · "
                + model.quantizationType() + " · " + bytes(size) + " · "
                + source(model.source()) + " · " + instant(model.createdAt());
    }

    static String localDetail(InferenceModelAsset asset, Set<InferenceModelProfile.Kind> purposes,
                              boolean loaded) {
        return "名称：" + asset.displayName() + "\n状态："
                + (loaded ? "已加载" : asset.state() == InferenceModelAsset.State.READY
                        ? "本地可用" : "失败 · " + asset.failure())
                + "\n用途：" + InferenceModelPurposeClassifier.purposeLabel(purposes)
                + "\n格式 / 量化：" + asset.format() + " / " + asset.quantizationType()
                + "\n量化大小：" + bytes(quantizedBytes(asset))
                + "\n来源：" + source(asset.source())
                + (asset.huggingFaceRepository().isBlank() ? "" : " · "
                        + asset.huggingFaceRepository() + "@" + asset.huggingFaceCommit())
                + "\n下载 / 导入时间：" + instant(asset.createdAt())
                + "\n插件内路径：" + asset.location();
    }

    static List<InferenceSettingsChoice<ServiceModel>> serviceModels(
            InferenceManagementApplicationService.Snapshot snapshot,
            Map<UUID, InferenceRuntimePort.RuntimeProfileStatus> statuses) {
        Map<UUID, List<String>> aliases = snapshot.publishedModels().stream()
                .filter(InferenceCatalogPort.PublishedModel::enabled)
                .collect(java.util.stream.Collectors.groupingBy(
                        InferenceCatalogPort.PublishedModel::profileId,
                        java.util.LinkedHashMap::new,
                        java.util.stream.Collectors.mapping(
                                InferenceCatalogPort.PublishedModel::alias,
                                java.util.stream.Collectors.toList())));
        return snapshot.profiles().stream().map(profile -> {
            List<String> publishedAliases = List.copyOf(
                    aliases.getOrDefault(profile.id(), List.of()));
            boolean loaded = statuses.containsKey(profile.id());
            String state = serviceState(profile, loaded, !publishedAliases.isEmpty());
            String aliasSummary = publishedAliases.isEmpty()
                    ? "尚未发布" : String.join(", ", publishedAliases);
            InferenceModelAsset asset = snapshot.assets().stream()
                    .filter(value -> value.id().equals(profile.assetId())).findFirst().orElse(null);
            InferenceRuntimePort.RuntimeProfileStatus runtime = statuses.get(profile.id());
            String backend = runtime == null || runtime.selectedBackend().isBlank()
                    ? String.valueOf(profile.loadParameters().getOrDefault("backend", "auto"))
                    : runtime.selectedBackend();
            String shape = profile.kind() == InferenceModelProfile.Kind.EMBEDDING
                    ? (runtime == null ? profile.embeddingDimensions()
                    : runtime.actualEmbeddingDimensions()) + " dim"
                    : (runtime == null ? profile.contextLength()
                    : runtime.actualContextLength()) + " context";
            ServiceModel model = new ServiceModel("profile:" + profile.id(), profile.id(),
                    publishedAliases, state, loaded);
            return new InferenceSettingsChoice<>(state + "  " + profile.name() + " · "
                    + kind(profile.kind()) + " · " + aliasSummary + " · "
                    + (asset == null ? "大小未知" : bytes(quantizedBytes(asset))) + " · "
                    + backend + " · " + shape, model);
        }).toList();
    }

    static ServiceDetails serviceDetails(
            InferenceManagementApplicationService.Snapshot snapshot,
            Map<UUID, InferenceRuntimePort.RuntimeProfileStatus> statuses,
            ServiceModel model, String endpoint) {
        InferenceModelProfile profile = snapshot.profiles().stream()
                .filter(value -> value.id().equals(model.profileId())).findFirst().orElse(null);
        if (profile == null) return ServiceDetails.EMPTY;
        InferenceModelAsset asset = snapshot.assets().stream()
                .filter(value -> value.id().equals(profile.assetId())).findFirst().orElse(null);
        InferenceRuntimePort.RuntimeProfileStatus status = statuses.get(profile.id());
        String alias = model.primaryAlias();
        String identifier = alias.isBlank() ? profile.name() : alias;
        String runtime = status == null ? "未驻留" : "已驻留 · "
                + (status.selectedBackend().isBlank() ? "auto" : status.selectedBackend());
        String shape = profile.kind() == InferenceModelProfile.Kind.EMBEDDING
                ? "向量维度 " + (status == null ? profile.embeddingDimensions()
                        : status.actualEmbeddingDimensions())
                : "上下文 " + (status == null ? profile.contextLength()
                        : status.actualContextLength());
        String text = "名称：" + profile.name() + "\n用途：" + kind(profile.kind())
                + "\n状态：" + model.state() + " · " + runtime + "\n别名："
                + (model.aliases().isEmpty() ? "尚未发布" : String.join(", ", model.aliases()))
                + "\n格式：" + (asset == null ? "未知" : asset.format()) + " · "
                + (asset == null ? "UNKNOWN" : asset.quantizationType())
                + "\n大小：" + (asset == null ? "未知" : bytes(quantizedBytes(asset)))
                + "\n" + shape;
        return new ServiceDetails(text, identifier, asset == null ? "" : asset.location(),
                curl(profile, alias, endpoint));
    }

    static String curl(InferenceModelProfile profile, String alias, String endpoint) {
        String base = endpoint == null || endpoint.isBlank()
                ? "http://127.0.0.1:18080/v1" : endpoint;
        if (alias == null || alias.isBlank()) return "请先为该模型发布 API 别名。";
        if (profile.kind() == InferenceModelProfile.Kind.EMBEDDING) {
            return "curl " + base + "/embeddings \\\n"
                    + "  -H 'Authorization: Bearer $JAVACLAW_API_KEY' \\\n"
                    + "  -H 'Content-Type: application/json' \\\n"
                    + "  -d '{\"model\":\"" + alias + "\",\"input\":[\"hello\"]}'";
        }
        return "curl " + base + "/chat/completions \\\n"
                + "  -H 'Authorization: Bearer $JAVACLAW_API_KEY' \\\n"
                + "  -H 'Content-Type: application/json' \\\n"
                + "  -d '{\"model\":\"" + alias
                + "\",\"messages\":[{\"role\":\"user\",\"content\":\"hello\"}]}'";
    }

    static String capacity(InferenceSystemProfilePort.SystemCapacity value) {
        String available = value.availableMemoryReliable()
                ? bytes(value.availablePhysicalBytes()) : "未能可靠读取";
        return "本机 " + value.logicalProcessors() + " 逻辑 CPU · 推荐 "
                + value.recommendedThreads() + " 线程 · 服务预留 "
                + bytes(value.recommendedReservedBytes()) + "（JVM "
                + bytes(value.recommendedHeapBytes()) + " / Native "
                + bytes(value.recommendedNativeBytes()) + "）· 当前可用 "
                + available + " · 安全模型预算 "
                + bytes(value.safeModelBudgetBytes());
    }

    static String filterLogs(List<String> logs, String filter) {
        return logs.stream().filter(line -> {
            boolean invocation = line.contains("[INFERENCE_CALL]");
            if ("调用日志".equals(filter)) return invocation;
            if ("运行日志".equals(filter)) return !invocation;
            return true;
        }).collect(java.util.stream.Collectors.joining("\n"));
    }

    static String bytes(long value) {
        if (value >= 1024L * 1024 * 1024) return String.format(Locale.ROOT, "%.2f GiB", value / 1073741824d);
        if (value >= 1024L * 1024) return String.format(Locale.ROOT, "%.1f MiB", value / 1048576d);
        if (value >= 1024L) return String.format(Locale.ROOT, "%.1f KiB", value / 1024d);
        return value + " B";
    }

    static String instant(Instant value) {
        return value == null || Instant.EPOCH.equals(value) ? "未知" : value.toString();
    }

    private static long quantizedBytes(InferenceModelAsset value) {
        return value.artifactMetadata().quantizedSizeBytes() > 0
                ? value.artifactMetadata().quantizedSizeBytes() : value.sizeBytes();
    }

    private static String source(InferenceModelAsset.Source value) {
        return value == InferenceModelAsset.Source.HUGGING_FACE ? "Hugging Face" : "本地导入";
    }

    private static String kind(InferenceModelProfile.Kind value) {
        return value == InferenceModelProfile.Kind.EMBEDDING ? "向量化" : "对话";
    }

    private static String serviceState(
            InferenceModelProfile profile, boolean loaded, boolean published) {
        if (loaded) return "已加载";
        if (profile.state() == InferenceModelProfile.State.FAILED) return "失败";
        if (published) return "已发布";
        return switch (profile.state()) {
            case READY -> "已配置";
            case DRAFT -> "草稿";
            case VERIFYING -> "验证中";
            case DISABLED -> "已停用";
            case FAILED -> "失败";
        };
    }

    record ServiceModel(String key, UUID profileId, List<String> aliases,
                        String state, boolean loaded) {
        ServiceModel {
            aliases = aliases == null ? List.of() : List.copyOf(aliases);
            state = state == null ? "CONFIGURED" : state;
        }

        String primaryAlias() { return aliases.isEmpty() ? "" : aliases.getFirst(); }
        boolean published() { return !aliases.isEmpty(); }
    }
    record ServiceDetails(String text, String identifier, String path, String curl) {
        private static final ServiceDetails EMPTY = new ServiceDetails("", "", "", "");
    }
}
