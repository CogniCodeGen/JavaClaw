package com.javaclaw.ui.javafx.settings;

import com.javaclaw.application.serviceplugin.ServicePluginManagementApplicationService.State;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Small imperative actions kept outside the service-console presentation controller. */
final class InferenceApiConsoleActions {
    private InferenceApiConsoleActions() { }

    static void copy(String value, String label, InferenceSettingsUiActions ui) {
        if (value == null || value.isBlank()) return;
        ClipboardContent content = new ClipboardContent();
        content.putString(value);
        Clipboard.getSystemClipboard().setContent(content);
        ui.status(label + "已复制");
    }

    static String routes(Map<String, Set<String>> endpointCapabilities) {
        Set<String> capabilities = endpointCapabilities.values().stream()
                .flatMap(Set::stream).collect(java.util.stream.Collectors.toSet());
        List<String> routes = new ArrayList<>();
        if (capabilities.contains("models")) routes.add("GET /v1/models");
        if (capabilities.contains("chat")) routes.add("POST /v1/chat/completions");
        if (capabilities.contains("embeddings")) routes.add("POST /v1/embeddings");
        if (capabilities.contains("sse")) routes.add("SSE stream=true");
        return routes.isEmpty() ? "插件未声明 OpenAI 路由" : String.join("   ·   ", routes);
    }

    static String stateText(State state) {
        return switch (state) {
            case HEALTHY -> "运行正常";
            case DEGRADED -> "降级运行";
            case STARTING -> "正在启动";
            case STOPPING -> "正在停止";
            case FAILED -> "启动失败";
            case QUARANTINED -> "已隔离";
            default -> "已停止";
        };
    }
}
