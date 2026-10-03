package com.javaclaw.application.settings;

import java.util.List;
import java.util.Objects;

/** 从当前表单查询提供商模型，不读取或写入持久化设置。调用方应在 I/O 线程执行并通过中断取消。 */
public interface ModelDiscoveryApplicationService {

    DiscoveryResult discover(DiscoveryRequest request) throws InterruptedException;

    enum Usage { CHAT, EMBEDDING }

    record DiscoveryRequest(String provider, String baseUrl, String apiKey, Usage usage) {
        public DiscoveryRequest {
            provider = normalize(provider);
            baseUrl = normalize(baseUrl);
            apiKey = normalize(apiKey);
            usage = Objects.requireNonNull(usage, "usage");
        }
    }

    record ModelOption(String id, String displayName) {
        public ModelOption {
            id = normalize(id);
            displayName = normalize(displayName);
            if (id.isEmpty()) throw new IllegalArgumentException("model id must not be empty");
            if (displayName.isEmpty()) displayName = id;
        }
    }

    record DiscoveryResult(boolean succeeded, List<ModelOption> models, String message) {
        public DiscoveryResult {
            models = List.copyOf(Objects.requireNonNull(models, "models"));
            message = normalize(message);
        }

        public static DiscoveryResult failed(String message) {
            return new DiscoveryResult(false, List.of(), message);
        }
    }

    private static String normalize(String value) {
        return value == null ? "" : value.strip();
    }
}
