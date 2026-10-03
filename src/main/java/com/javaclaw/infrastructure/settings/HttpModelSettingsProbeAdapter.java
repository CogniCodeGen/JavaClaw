package com.javaclaw.infrastructure.settings;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.javaclaw.application.settings.ModelDiscoveryApplicationService;
import com.javaclaw.application.settings.ModelDiscoveryApplicationService.DiscoveryRequest;
import com.javaclaw.application.settings.ModelDiscoveryApplicationService.Usage;
import com.javaclaw.application.settings.ModelDiscoveryUseCase;
import com.javaclaw.application.settings.ModelSettingsApplicationService.EmbeddingSettings;
import com.javaclaw.application.settings.ModelSettingsApplicationService.ModelSettings;
import com.javaclaw.application.settings.ModelSettingsApplicationService.ProbeResult;
import com.javaclaw.application.settings.EmbeddingRuntimeProbePort;
import com.javaclaw.application.settings.ModelSettingsProbePort;
import com.javaclaw.platform.http.HttpGateway;
import com.javaclaw.platform.http.HttpRetryPolicy;
import com.javaclaw.platform.json.JsonCodec;
import com.javaclaw.application.settings.DefaultModelProviderCatalog;
import com.javaclaw.config.CredentialUsage;
import com.javaclaw.inference.api.InferenceChatRequest;
import com.javaclaw.inference.api.InferenceEmbeddingRequest;
import com.javaclaw.inference.api.InferenceMessage;
import com.javaclaw.inference.api.LocalInferenceGateway;

import java.net.URI;
import java.net.http.HttpRequest;
import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.List;
import java.util.UUID;

/** 通过共享 HTTP 网关或当前工作区 EmbeddingGateway 探测模型连接。 */
public final class HttpModelSettingsProbeAdapter implements ModelSettingsProbePort {

    private final HttpGateway http;
    private final JsonCodec json;
    private final EmbeddingRuntimeProbePort runtimeEmbedding;
    private final LocalInferenceGateway localInference;
    private final ModelDiscoveryApplicationService discovery;

    public HttpModelSettingsProbeAdapter(
            HttpGateway http, JsonCodec json, EmbeddingRuntimeProbePort runtimeEmbedding) {
        this(http, json, runtimeEmbedding, null);
    }

    public HttpModelSettingsProbeAdapter(
            HttpGateway http, JsonCodec json, EmbeddingRuntimeProbePort runtimeEmbedding,
            LocalInferenceGateway localInference) {
        this(http, json, runtimeEmbedding, localInference,
                new ModelDiscoveryUseCase(new DefaultModelProviderCatalog(),
                        new HttpModelDiscoveryAdapter(http, json)));
    }

    public HttpModelSettingsProbeAdapter(
            HttpGateway http, JsonCodec json, EmbeddingRuntimeProbePort runtimeEmbedding,
            LocalInferenceGateway localInference, ModelDiscoveryApplicationService discovery) {
        this.http = Objects.requireNonNull(http, "http");
        this.json = Objects.requireNonNull(json, "json");
        this.runtimeEmbedding = Objects.requireNonNull(runtimeEmbedding, "runtimeEmbedding");
        this.localInference = localInference;
        this.discovery = Objects.requireNonNull(discovery, "discovery");
    }

    @Override
    public ProbeResult probeModel(ModelSettings settings) throws Exception {
        if (managed(settings.provider())) {
            requireLocal();
            long started = System.nanoTime();
            var response = localInference.chat(new InferenceChatRequest(UUID.randomUUID().toString(),
                    UUID.fromString(settings.managedProfileId()),
                    List.of(new InferenceMessage(InferenceMessage.Role.USER,
                            "只回复 OK", "", "", List.of())), List.of(),
                    Map.of("maxTokens", 4, "temperature", 0),
                    Duration.ofSeconds(settings.readTimeoutSeconds())));
            return new ProbeResult(true, "✓ 本地推理正常 · " + response.model()
                    + " · " + elapsedMillis(started) + "ms · "
                    + response.usage().totalTokens() + " tokens");
        }
        long started = System.nanoTime();
        var result = discovery.discover(new DiscoveryRequest(settings.provider(),
                settings.baseUrl(), settings.apiKey(), Usage.CHAT));
        if (!result.succeeded()) return new ProbeResult(false, result.message());
        return new ProbeResult(true, "✓ 模型列表接口可达（尚未验证聊天调用） · "
                + elapsedMillis(started) + "ms");
    }

    @Override
    public ProbeResult probeEmbedding(
            EmbeddingSettings form, EmbeddingSettings persisted) throws Exception {
        if (managed(form.provider())) {
            requireLocal();
            long started = System.nanoTime();
            var response = localInference.embeddings(new InferenceEmbeddingRequest(
                    UUID.randomUUID().toString(), UUID.fromString(form.managedProfileId()),
                    List.of("嵌入连通性测试"), Duration.ofMinutes(5)));
            return dimensions(form, response.dimensions(), elapsedMillis(started), !form.equals(persisted));
        }
        try {
            CredentialUsage.requirePlaintext(form.apiKey());
        } catch (IllegalStateException unreadable) {
            return new ProbeResult(false, unreadable.getMessage());
        }
        if (form.equals(persisted) && runtimeEmbedding.isReady()) {
            long started = System.nanoTime();
            var health = runtimeEmbedding.probe();
            long elapsed = elapsedMillis(started);
            if (health.healthy()) {
                return dimensions(form, health.dimensions(), elapsed, false);
            }
            String error = health.error().isBlank() ? "嵌入模型不可用" : health.error();
            return new ProbeResult(false, "嵌入测试失败: " + error);
        }

        String provider = new DefaultModelProviderCatalog().normalizeId(form.provider());
        if ("anthropic".equals(provider) || "gemini".equals(provider)) {
            return new ProbeResult(false, "该提供商当前不支持嵌入模型连接测试");
        }
        // The runtime uses OpenAiEmbeddingModel for external providers, including Ollama.
        // Its Ollama base URL is normalized to /v1 before building the request.
        String body = json.encode(Map.of("model", form.modelName(),
                "input", "嵌入连通性测试"));
        URI endpoint = endpoint("ollama".equals(provider) ? ollamaCompatibleBase(form.baseUrl())
                : form.baseUrl(), "embeddings");
        long started = System.nanoTime();
        com.javaclaw.platform.http.HttpResult response;
        try {
            response = http.sendAndWait("settings-embedding-probe", () -> {
                HttpRequest.Builder request = HttpRequest.newBuilder(endpoint)
                        .timeout(Duration.ofSeconds(5))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body));
                if (!form.apiKey().isBlank() && !"not-needed".equals(form.apiKey())) {
                    request.header("Authorization", "Bearer " + form.apiKey());
                }
                return request.build();
            }, HttpRetryPolicy.none());
        } catch (IOException unreachable) {
            return new ProbeResult(false, "嵌入测试失败：无法连接接口，请检查 API 地址和网络");
        } catch (IllegalArgumentException invalidRequest) {
            return new ProbeResult(false, "嵌入测试失败：API Key 或 API 地址格式无效");
        }
        long elapsed = elapsedMillis(started);
        if (!endpoint.equals(response.uri())) {
            return new ProbeResult(false, "嵌入接口发生重定向，请直接填写最终 API 地址");
        }
        if (!response.isSuccessful()) {
            return new ProbeResult(false, "嵌入测试失败: HTTP " + response.statusCode());
        }
        JsonNode result;
        try {
            result = json.tree(response.bodyText());
        } catch (JsonProcessingException invalidJson) {
            return new ProbeResult(false, "嵌入测试失败：接口未返回有效 JSON");
        }
        int actualDimensions = result.path("data").path(0).path("embedding").size();
        if (actualDimensions <= 0) {
            return new ProbeResult(false, "嵌入测试失败: 响应中未找到嵌入向量");
        }
        return dimensions(form, actualDimensions, elapsed, !form.equals(persisted));
    }

    private static ProbeResult dimensions(
            EmbeddingSettings form, int actual, long elapsed, boolean unsaved) {
        if (actual != form.dimensions()) {
            return new ProbeResult(false, "当前表单配置可用，但实际维度 " + actual
                    + " 与配置 " + form.dimensions() + " 不一致，写入向量库会失败，请修正向量维度");
        }
        return new ProbeResult(true, "✓ 当前表单配置可用 · 维度 " + actual + " · "
                + elapsed + "ms" + (unsaved ? "（尚未保存）" : ""));
    }

    private static URI endpoint(String baseUrl, String suffix) {
        String base = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        if (base.endsWith("/" + suffix)) return URI.create(base);
        return URI.create(base + "/" + suffix);
    }

    private static String ollamaCompatibleBase(String baseUrl) {
        String base = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        return base.endsWith("/v1") ? base : base + "/v1";
    }

    private static long elapsedMillis(long started) {
        return (System.nanoTime() - started) / 1_000_000L;
    }

    private void requireLocal() {
        if (localInference == null) throw new IllegalStateException("本地推理网关未装配");
    }

    private static boolean managed(String provider) {
        return DefaultModelProviderCatalog.DELIVERANCE.equalsIgnoreCase(provider);
    }
}
