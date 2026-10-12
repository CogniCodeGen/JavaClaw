package com.javaclaw.plugins.echo;

import com.google.gson.*;
import com.javaclaw.plugin.protocol.v2.CapabilityKind;
import com.javaclaw.plugin.protocol.v2.Contribution;
import com.javaclaw.plugin.sdk.*;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import static com.javaclaw.plugins.echo.EchoModelFormat.*;

/** 确定性的 MODEL 契约样例；回声和哈希向量仅用于集成验证。 */
public final class EchoModelPlugin implements Plugin {
    private static final Gson JSON = new Gson();
    private static final List<String> CAPABILITIES = List.of("chat", "streaming", "embeddings", "cancellation",
            "exact_usage", "terminal_usage", "model-type:generation:echo", "model-type:embedding:echo");
    private final Map<String, Loaded> loaded = new ConcurrentHashMap<>();
    private final JsonObject manifest = readManifest();
    private volatile String configuredPrefix;

    @Override public List<Contribution> contributions() {
        return List.of(Contribution.newBuilder().setId("inference").setKind(CapabilityKind.MODEL)
                .setName("Echo 协议验证模型").setDescription("确定性回声与哈希向量，不执行语言模型推理")
                .setInputSchemaJson("{\"type\":\"object\"}")
                .setMetadataJson(manifest.getAsJsonObject("inference").toString()).build());
    }
    @Override public List<String> validateConfiguration(String configuration) {
        var value = JsonParser.parseString(configuration).getAsJsonObject();
        return text(value, "prefix", "").length() > 128 ? List.of("回复前缀不能超过 128 个字符") : List.of();
    }
    @Override public void start(PluginContext context) {
        var configuration = JsonParser.parseString(context.configurationJson()).getAsJsonObject();
        configuredPrefix = configuration.has("prefix") ? text(configuration, "prefix", "") : null;
    }

    @Override public void execute(PluginCall call, PluginEvents events) throws Exception {
        call.cancellation().throwIfCancelled();
        var body = JsonParser.parseString(call.payloadJson()).getAsJsonObject();
        switch (call.request().getOperation()) {
            case "load" -> {
                String id = profile(body);
                if (!loaded.containsKey(id) && loaded.size() >= 64) throw new IllegalStateException("模型实例数量已达上限");
                var startup = body.getAsJsonObject("startup");
                var model = inspect(text(startup, "modelPath", ""));
                int context = number(startup, "contextLength", model.contextLength());
                if (context == 0) context = model.contextLength();
                if (context < 1 || context > model.contextLength()) throw new IllegalArgumentException("模型上下文无效");
                var value = new Loaded(model, context, text(startup, "modelName", model.name()), modelKind(startup));
                loaded.put(id, value); events.data(JSON.toJson(Map.of("model", status(id, value, true))));
            }
            case "unload" -> events.data(JSON.toJson(Map.of("profileId", profile(body), "unloaded", loaded.remove(profile(body)) != null)));
            case "list" -> events.data(JSON.toJson(Map.of("models", loaded.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey()).map(entry -> status(entry.getKey(), entry.getValue(), true)).toList())));
            case "probe" -> {
                if (!body.has("startup")) events.data("{\"state\":\"HEALTHY\"}");
                else {
                    var startup = body.getAsJsonObject("startup");
                    var model = inspect(text(startup, "modelPath", ""));
                    int requested = number(startup, "contextLength", model.contextLength());
                    int context = requested <= 0 ? model.contextLength() : Math.min(model.contextLength(), requested);
                    events.data(JSON.toJson(Map.of("model", status(profile(body), new Loaded(model, context, model.name(), modelKind(startup)), false))));
                }
            }
            case "chat" -> chat(call, body, events);
            case "embedding" -> embedding(call, body, events);
            case "model.inspect" -> {
                var model = inspect(text(body, "modelPath", ""));
                events.data(JSON.toJson(Map.of("modelType", "echo", "architectures", List.of("DeterministicEchoFixture"),
                        "declaredContextLength", model.contextLength())));
            }
            case "asset.inspect" -> {
                var model = inspect(text(body, "modelPath", ""));
                events.data(JSON.toJson(Map.of("format", "ECHO_JSON", "quantizationType", "NONE", "sourceSizeBytes", model.sizeBytes(),
                        "quantizedSizeBytes", model.sizeBytes(), "sourceUpdatedAt", "1970-01-01T00:00:00Z")));
            }
            case "recommend" -> events.data(JSON.toJson(recommend(body)));
            case "catalog.search" -> {
                String query = text(body, "query", "").strip().toLowerCase(Locale.ROOT);
                boolean match = query.isBlank() || "javaclaw/echo-fixture".contains(query);
                events.data(JSON.toJson(Map.of("models", match && text(body, "cursor", "").isBlank()
                        ? List.of(catalogSummary()) : List.of(), "nextCursor", "")));
            }
            case "catalog.detail" -> {
                if (!text(body, "repository", "").equals("javaclaw/echo-fixture"))
                    throw new IllegalArgumentException("未知协议夹具目录");
                events.data(JSON.toJson(Map.of("summary", catalogSummary(), "license", "协议验证夹具，需从本地导入",
                        "architectures", List.of("DeterministicEchoFixture"), "fileCount", 1,
                        "declaredContextLength", 4096, "url", "")));
            }
            case "models.sync", "models.publish" -> events.data("{}");
            case "gateway.configure" -> {
                if (body.has("enabled") && body.get("enabled").getAsBoolean())
                    throw new IllegalArgumentException("协议验证模型不提供公开 HTTP 接口");
                events.data("{\"enabled\":false}");
            }
            case "gateway.status", "logging.configure", "logging.status" -> events.data("{\"enabled\":false}");
            default -> throw new IllegalArgumentException("模型操作未注册");
        }
    }

    private void chat(PluginCall call, JsonObject body, PluginEvents events) throws Exception {
        Loaded model = requireLoaded(body);
        JsonObject request = body.getAsJsonObject("request");
        String id = text(request, "requestId", ""), prompt = "";
        for (JsonElement item : request.getAsJsonArray("messages")) {
            var message = item.getAsJsonObject();
            if (text(message, "role", "").equalsIgnoreCase("user")) prompt = text(message, "content", "");
        }
        int promptTokens = prompt.codePointCount(0, prompt.length());
        if (promptTokens > model.context) throw new IllegalArgumentException("输入超过模型上下文");
        JsonObject parameters = request.has("parameters") ? request.getAsJsonObject("parameters") : new JsonObject();
        int max = number(parameters, "maxTokens", model.context), delay = number(parameters, "delayMillis", 0);
        if (max < 1 || max > model.context || delay < 0 || delay > 1000) throw new IllegalArgumentException("生成参数超出范围");
        String full = (configuredPrefix == null ? model.descriptor.prefix() : configuredPrefix) + prompt;
        int[] codePoints = full.codePoints().limit(max).toArray();
        String content = new String(codePoints, 0, codePoints.length);
        var usage = Map.of("promptTokens", promptTokens, "completionTokens", codePoints.length);
        var response = new LinkedHashMap<String, Object>();
        response.put("requestId", id); response.put("model", model.name); response.put("content", content);
        response.put("reasoningContent", ""); response.put("toolCalls", List.of()); response.put("usage", usage);
        response.put("finishReason", full.codePointCount(0, full.length()) > max ? "LENGTH" : "STOP");
        response.put("queueTimeMs", 0); response.put("inferenceTimeMs", 0);
        if (request.has("stream") && request.get("stream").getAsBoolean()) {
            for (int point : codePoints) {
                call.cancellation().throwIfCancelled();
                events.data(JSON.toJson(Map.of("requestId", id, "type", "DELTA", "text", new String(Character.toChars(point)))));
                if (delay > 0) Thread.sleep(delay);
            }
            events.data(JSON.toJson(Map.of("requestId", id, "type", "COMPLETE", "text", "", "usage", usage, "response", response)));
        }
        call.cancellation().throwIfCancelled();
        events.data(JSON.toJson(response));
    }

    private void embedding(PluginCall call, JsonObject body, PluginEvents events) throws Exception {
        Loaded model = requireLoaded(body);
        JsonObject request = body.getAsJsonObject("request");
        JsonArray input = request.getAsJsonArray("input");
        if (input.size() > 128) throw new IllegalArgumentException("嵌入批量超出范围");
        var vectors = new ArrayList<float[]>();
        long tokens = 0;
        for (JsonElement item : input) {
            call.cancellation().throwIfCancelled();
            String text = item.getAsString();
            int size = text.codePointCount(0, text.length());
            if (size > model.context) throw new IllegalArgumentException("嵌入输入超过模型上下文");
            tokens += size;
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            float[] vector = new float[model.descriptor.dimensions()];
            for (int i = 0; i < vector.length; i++) vector[i] = ((digest[i % digest.length] & 255) - 127.5f) / 127.5f;
            vectors.add(vector);
        }
        events.data(JSON.toJson(Map.of("requestId", text(request, "requestId", ""), "model", model.name,
                "dimensions", model.descriptor.dimensions(), "embeddings", vectors,
                "usage", Map.of("promptTokens", tokens, "completionTokens", 0), "inferenceTimeMs", 0)));
    }

    private Loaded requireLoaded(JsonObject body) {
        return Objects.requireNonNull(loaded.get(profile(body)), "模型尚未加载");
    }
    private String profile(JsonObject body) {
        String id = text(body, "profileId", "");
        if (id.isBlank() || id.length() > 256) throw new IllegalArgumentException("模型档案标识无效");
        return id;
    }
    private Map<String, Object> status(String id, Loaded value, boolean active) {
        return Map.of("profileId", id, "name", value.name, "kind", value.kind, "loaded", active,
                "actualContextLength", value.context, "actualEmbeddingDimensions", value.descriptor.dimensions(),
                "selectedBackend", "deterministic-fixture", "capabilities", CAPABILITIES);
    }
    private String modelKind(JsonObject startup) {
        String kind = text(startup, "modelKind", "GENERATION");
        if (!Set.of("GENERATION", "EMBEDDING").contains(kind)) throw new IllegalArgumentException("模型用途无效");
        return kind;
    }
    private Map<String, Object> catalogSummary() {
        return Map.of("repository", "javaclaw/echo-fixture", "commit", "", "modelType", "echo", "quantizationType", "NONE",
                "sourceSizeBytes", 0, "quantizedSizeBytes", 0, "lastModified", "1970-01-01T00:00:00Z", "gated", true);
    }
    private Map<String, Object> recommend(JsonObject request) {
        JsonObject model = request.has("model") ? request.getAsJsonObject("model") : new JsonObject();
        int context = Math.max(1, number(model, "declaredContextLength", 4096));
        return Map.of("contextLength", context, "loadParameters", Map.of(), "generationParameters", Map.of("maxTokens", context, "delayMillis", 0),
                "capacity", Map.of("logicalProcessors", 1, "recommendedThreads", 1, "physicalMemoryAvailable", false, "availableMemoryReliable", false),
                "memory", Map.of("modelBytes", 0, "estimatedRequiredBytes", 0, "exceedsPhysicalMemory", false,
                        "exceedsSafeBudget", false, "message", "确定性协议夹具不加载模型权重"));
    }
    private JsonObject readManifest() {
        try (var input = new InputStreamReader(Objects.requireNonNull(getClass().getResourceAsStream("/plugin.json")), StandardCharsets.UTF_8)) {
            return JsonParser.parseReader(input).getAsJsonObject();
        } catch (Exception failure) { throw new IllegalStateException("插件清单无效", failure); }
    }
    @Override public void close() { loaded.clear(); }
    private record Loaded(Descriptor descriptor, int context, String name, String kind) { }
}
