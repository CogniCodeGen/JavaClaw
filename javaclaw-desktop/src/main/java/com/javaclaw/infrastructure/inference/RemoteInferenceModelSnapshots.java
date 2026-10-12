package com.javaclaw.infrastructure.inference;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.javaclaw.inference.api.InferenceModelAsset;
import com.javaclaw.inference.api.InferenceModelProfile;
import com.javaclaw.inference.api.InferenceProviderSnapshot;
import com.javaclaw.inference.api.LocalInferenceGateway.InferenceException;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 解析 Run 冻结的模型档案与资产；内容派生的内部标识隔离同一实例中的不同档案配置。 */
final class RemoteInferenceModelSnapshots {
    private RemoteInferenceModelSnapshots() { }
    record Model(InferenceModelProfile profile, InferenceModelAsset asset) { }
    record Binding(Map<String, InferenceProviderSnapshot> providers, Map<UUID, Model> models) {
        Model require(UUID id) throws InferenceException {
            Model model = models.get(id);
            if (model == null) throw new InferenceException("model_snapshot_missing", "Run 未冻结此模型档案与资产，禁止读取当前目录", false);
            return model;
        }
    }
    static Binding read(List<InferenceProviderSnapshot> snapshots, ObjectMapper json) {
        Map<String, InferenceProviderSnapshot> providers = new LinkedHashMap<>();
        Map<UUID, Model> models = new LinkedHashMap<>();
        for (InferenceProviderSnapshot snapshot : List.copyOf(snapshots)) {
            if (providers.putIfAbsent(snapshot.pluginId(), snapshot) != null) throw new IllegalArgumentException("模型提供者快照重复");
            try {
                JsonNode frozen = json.readTree(snapshot.hostConfigurationJson());
                if (frozen == null || !frozen.isObject()) throw new IllegalArgumentException("模型配置快照不是 JSON 对象");
                if (!frozen.has("profiles") && !frozen.has("assets")) continue;
                if (!frozen.path("profiles").isArray() || !frozen.path("assets").isArray()) throw new IllegalArgumentException("模型快照缺少档案或资产数组");
                Map<UUID, JsonNode> assets = new LinkedHashMap<>();
                for (JsonNode node : frozen.path("assets")) {
                    UUID id = UUID.fromString(node.path("id").asText());
                    if (assets.putIfAbsent(id, node) != null) throw new IllegalArgumentException("模型资产快照重复");
                }
                for (JsonNode node : frozen.path("profiles")) {
                    InferenceModelProfile original = json.treeToValue(node, InferenceModelProfile.class);
                    if (!snapshot.pluginId().equals(original.runtimeId())) throw new IllegalArgumentException("模型档案与提供者快照不匹配");
                    JsonNode assetNode = assets.get(original.assetId());
                    if (assetNode == null) throw new IllegalArgumentException("模型快照缺少档案引用的资产");
                    InferenceModelAsset asset = json.treeToValue(assetNode, InferenceModelAsset.class);
                    UUID effective = UUID.nameUUIDFromBytes((snapshot.pluginId() + ":" + node + ":" + assetNode).getBytes(StandardCharsets.UTF_8));
                    var profile = new InferenceModelProfile(effective, original.name(), original.kind(), original.assetId(), original.runtimeId(),
                            original.loadParameters(), original.defaultParameters(), original.contextLength(), original.embeddingDimensions(),
                            original.state(), original.failure(), original.createdAt(), original.updatedAt());
                    if (models.putIfAbsent(original.id(), new Model(profile, asset)) != null) throw new IllegalArgumentException("模型档案快照重复");
                }
            } catch (java.io.IOException failure) { throw new IllegalArgumentException("模型配置快照无法读取", failure); }
        }
        return new Binding(Map.copyOf(providers), Map.copyOf(models));
    }
}
