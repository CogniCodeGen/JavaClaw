package com.javaclaw.plugins.deliverance.catalog;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.javaclaw.plugin.sdk.PluginCall;
import com.javaclaw.plugin.sdk.PluginEvents;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/** 模型目录、量化工件解析和引擎参数推荐全部留在插件进程。 */
public final class DeliveranceCatalogOperations {
    private final ObjectMapper json;
    private final HuggingFaceModelCatalogPort catalog;
    private final InferenceSystemProfilePort recommendations = new JvmInferenceSystemProfileAdapter();
    private final Set<String> modelTypes;

    public DeliveranceCatalogOperations(ObjectMapper json, JsonNode inference) {
        this.json = json;
        this.catalog = new HuggingFaceModelCatalogClient(
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build(), json);
        Set<String> supported = new LinkedHashSet<>();
        inference.path("capabilities").forEach(value -> {
            String capability = value.asText();
            if (capability.startsWith("model-type:")) {
                supported.add(capability.substring(capability.lastIndexOf(':') + 1));
            }
        });
        this.modelTypes = Set.copyOf(supported);
    }

    public boolean supports(String operation) {
        return Set.of("catalog.search", "catalog.detail", "recommend", "asset.inspect", "model.inspect").contains(operation);
    }

    public void execute(PluginCall call, PluginEvents events) throws Exception {
        JsonNode value = json.readTree(call.payloadJson());
        call.cancellation().throwIfCancelled();
        Object result = switch (call.request().getOperation()) {
            case "catalog.search" -> catalog.search(new HuggingFaceModelCatalogPort.SearchRequest(
                    value.path("query").asText(""), value.path("cursor").asText(""),
                    value.path("limit").asInt(20)), modelTypes, call.cancellation()::isCancelled);
            case "catalog.detail" -> catalog.detail(value.path("repository").asText(),
                    modelTypes, call.cancellation()::isCancelled);
            case "recommend" -> recommendations.recommend(json.treeToValue(value.path("model"),
                    InferenceModelMetadataPort.ModelMetadata.class),
                    value.hasNonNull("artifact") ? json.treeToValue(value.path("artifact"),
                            InferenceModelAsset.ArtifactMetadata.class) : null);
            case "asset.inspect" -> inspect(value);
            case "model.inspect" -> new InferenceModelMetadataReader(json).inspect(
                    Path.of(value.path("modelPath").asText()), call.cancellation()::isCancelled);
            default -> throw new IllegalArgumentException("不支持的模型目录操作");
        };
        call.cancellation().throwIfCancelled();
        events.data(json.writeValueAsString(result));
    }

    private InferenceModelAsset.ArtifactMetadata inspect(JsonNode request) throws Exception {
        long bytes = Math.max(0, request.path("quantizedSizeBytes").asLong(request.path("sizeBytes").asLong(0)));
        Path root = Path.of(request.path("modelPath").asText()).toAbsolutePath().normalize();
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(root)) {
            throw new IllegalArgumentException("模型目录无效");
        }
        Path manifest = root.resolve(HuggingFaceModelCatalogClient.QUANTIZATION_MANIFEST);
        if (Files.isSymbolicLink(manifest) || !Files.isRegularFile(manifest, LinkOption.NOFOLLOW_LINKS)
                || Files.size(manifest) <= 0 || Files.size(manifest) > 1024 * 1024) {
            return InferenceModelAsset.ArtifactMetadata.unknown(bytes);
        }
        JsonNode value = json.readTree(manifest.toFile());
        String target = value.path("targetType").asText("").strip().toUpperCase(Locale.ROOT);
        if (!value.isObject() || value.path("schemaVersion").asInt(-1) != 1
                || (!target.equals("Q4") && !target.equals("I8"))) {
            return InferenceModelAsset.ArtifactMetadata.unknown(bytes);
        }
        return new InferenceModelAsset.ArtifactMetadata("SAFETENSORS", target,
                Math.max(0, value.path("sourceSizeBytes").asLong(0)), bytes, Instant.EPOCH);
    }
}
