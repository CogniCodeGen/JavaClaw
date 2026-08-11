package com.javaclaw.infrastructure.settings;

import com.javaclaw.application.settings.ModelSettingsApplicationService.EmbeddingSettings;
import com.javaclaw.application.settings.ModelSettingsApplicationService.ModelSettings;
import com.javaclaw.application.settings.ModelSettingsApplicationService.ProbeResult;
import com.javaclaw.application.settings.EmbeddingRuntimeProbePort;
import com.javaclaw.application.settings.ModelSettingsProbePort;
import com.javaclaw.platform.http.HttpGateway;
import com.javaclaw.platform.http.HttpRetryPolicy;
import com.javaclaw.platform.json.JsonCodec;

import java.net.URI;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;

/** 通过共享 HTTP 网关或当前工作区 EmbeddingGateway 探测模型连接。 */
public final class HttpModelSettingsProbeAdapter implements ModelSettingsProbePort {

    private final HttpGateway http;
    private final JsonCodec json;
    private final EmbeddingRuntimeProbePort runtimeEmbedding;

    public HttpModelSettingsProbeAdapter(
            HttpGateway http, JsonCodec json, EmbeddingRuntimeProbePort runtimeEmbedding) {
        this.http = Objects.requireNonNull(http, "http");
        this.json = Objects.requireNonNull(json, "json");
        this.runtimeEmbedding = Objects.requireNonNull(runtimeEmbedding, "runtimeEmbedding");
    }

    @Override
    public ProbeResult probeModel(ModelSettings settings) throws Exception {
        long started = System.nanoTime();
        URI endpoint = endpoint(settings.baseUrl(), "models");
        var response = http.sendAndWait("settings-model-probe", () -> {
            HttpRequest.Builder request = HttpRequest.newBuilder(endpoint)
                    .timeout(Duration.ofSeconds(5)).GET();
            if (!settings.apiKey().isBlank() && !"not-needed".equals(settings.apiKey())) {
                request.header("Authorization", "Bearer " + settings.apiKey());
            }
            return request.build();
        }, HttpRetryPolicy.none());
        long elapsed = elapsedMillis(started);
        if (response.statusCode() >= 200 && response.statusCode() < 400) {
            return new ProbeResult(true,
                    "✓ 连接正常 · " + settings.modelName() + " · " + elapsed + "ms");
        }
        return new ProbeResult(false, "连接异常 (HTTP " + response.statusCode() + ")");
    }

    @Override
    public ProbeResult probeEmbedding(
            EmbeddingSettings form, EmbeddingSettings persisted) throws Exception {
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

        String body = json.encode(Map.of(
                "model", form.modelName(), "input", "嵌入连通性测试"));
        URI endpoint = endpoint(form.baseUrl(), "embeddings");
        long started = System.nanoTime();
        var response = http.sendAndWait("settings-embedding-probe", () -> {
            HttpRequest.Builder request = HttpRequest.newBuilder(endpoint)
                    .timeout(Duration.ofSeconds(5))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body));
            if (!form.apiKey().isBlank()) {
                request.header("Authorization", "Bearer " + form.apiKey());
            }
            return request.build();
        }, HttpRetryPolicy.none());
        long elapsed = elapsedMillis(started);
        if (!response.isSuccessful()) {
            return new ProbeResult(false, "嵌入测试失败: HTTP " + response.statusCode());
        }
        int actualDimensions = json.tree(response.bodyText())
                .path("data").path(0).path("embedding").size();
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
        return URI.create(baseUrl.endsWith("/") ? baseUrl + suffix : baseUrl + "/" + suffix);
    }

    private static long elapsedMillis(long started) {
        return (System.nanoTime() - started) / 1_000_000L;
    }
}
