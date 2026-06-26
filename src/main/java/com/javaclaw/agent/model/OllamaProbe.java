package com.javaclaw.agent.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Ollama 本地服务探测器 — 离线模式下检查服务是否可用、已下载哪些模型
 */
public final class OllamaProbe {

    private static final Logger log = LoggerFactory.getLogger(OllamaProbe.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public record Status(boolean reachable, List<String> installedModels, String error) {
        public static Status unreachable(String reason) {
            return new Status(false, List.of(), reason);
        }
    }

    private OllamaProbe() {}

    /**
     * 探测 Ollama 服务
     *
     * @param baseUrl 形如 {@code http://localhost:11434/v1} 或 {@code http://localhost:11434}
     */
    public static Status probe(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            return Status.unreachable("baseUrl 为空");
        }
        String apiUrl = baseUrl.replaceAll("/v1/?$", "").replaceAll("/+$", "") + "/api/tags";
        try {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(3)).build();
            HttpRequest req = HttpRequest.newBuilder(URI.create(apiUrl))
                    .timeout(Duration.ofSeconds(5)).GET().build();
            HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                return Status.unreachable("HTTP " + resp.statusCode());
            }
            JsonNode root = MAPPER.readTree(resp.body());
            JsonNode models = root.path("models");
            List<String> names = new ArrayList<>();
            if (models.isArray()) {
                for (JsonNode m : models) {
                    String name = m.path("name").asText(null);
                    if (name != null) names.add(name);
                }
            }
            return new Status(true, names, null);
        } catch (Exception e) {
            log.debug("Ollama 探测失败: {}", e.getMessage());
            return Status.unreachable(e.getMessage());
        }
    }
}
