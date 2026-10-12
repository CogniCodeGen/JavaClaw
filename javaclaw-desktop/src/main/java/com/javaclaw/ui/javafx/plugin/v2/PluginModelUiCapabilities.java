package com.javaclaw.ui.javafx.plugin.v2;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.javaclaw.application.plugin.v2.PluginManagementService.PluginDetails;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/** 仅投影 MODEL 贡献显式声明的宿主标准接口能力，不从模型能力猜测 HTTP 服务。 */
final class PluginModelUiCapabilities {
    private static final Set<String> ROUTES = Set.of("models", "chat", "embeddings", "sse", "openapi");
    private PluginModelUiCapabilities() { }

    static Map<String, Set<String>> endpoints(PluginDetails details, ObjectMapper json) {
        Set<String> routes = new LinkedHashSet<>();
        details.contributions().stream().filter(value -> "MODEL".equals(value.capability())).forEach(contribution -> {
            try {
                JsonNode metadata = json.readTree(contribution.metadataJson());
                if (metadata == null) return;
                JsonNode inference = metadata.has("inference") ? metadata.path("inference") : metadata;
                Set<String> model = new LinkedHashSet<>();
                inference.path("capabilities").forEach(value -> model.add(value.asText()));
                JsonNode declared = inference.path("endpointCapabilities").path("openai");
                if (!declared.isArray()) return;
                declared.forEach(value -> {
                    String route = value.asText();
                    if (!ROUTES.contains(route) || route.equals("chat") && !model.contains("chat")
                            || route.equals("embeddings") && !model.contains("embeddings")
                            || route.equals("sse") && !(model.contains("chat") && model.contains("streaming"))) return;
                    routes.add(route);
                });
            } catch (java.io.IOException invalid) {
                // 无法解析的能力声明按未声明处理，不阻断宿主配置页。
            }
        });
        if (!routes.contains("chat")) routes.remove("sse");
        return routes.isEmpty() ? Map.of() : Map.of("openai", Set.copyOf(routes));
    }
}
