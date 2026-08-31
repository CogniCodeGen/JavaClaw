package com.javaclaw.server.model;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

import com.javaclaw.agent.model.CompactionStrategy;
import com.javaclaw.agent.model.EmbeddingGateway;
import com.javaclaw.agent.model.ModelGateway;
import com.javaclaw.agent.model.NativeCompactionResult;
import com.javaclaw.core.api.ModelRequest;
import com.javaclaw.core.api.ModelResponse;
import com.javaclaw.core.api.ModelStreamSink;
import com.javaclaw.core.api.TurnConfig;
import com.javaclaw.server.security.SecretStore;

/** Hot-reload boundary for three cloud providers; old clients are closed after an atomic swap. */
public final class ReloadableCloudModelGateway implements ModelGateway, EmbeddingGateway, AutoCloseable {
    private static final List<String> PROVIDERS = List.of("openai", "anthropic", "google");
    private final SecretStore secrets;
    private final Map<String, String> processEnvironment;
    private final AtomicReference<SpringAiCloudModelGateway> delegate = new AtomicReference<>();
    private volatile Map<String, Map<String, String>> providerConfiguration = Map.of();

    /** 保存凭据边界与进程环境快照；环境变量仅作为临时覆盖，不写入持久配置。 */
    public ReloadableCloudModelGateway(SecretStore secrets, Map<String, String> processEnvironment) {
        this.secrets = Objects.requireNonNull(secrets, "secrets");
        this.processEnvironment = Map.copyOf(Objects.requireNonNull(processEnvironment, "processEnvironment"));
        reload(Map.of());
    }

    /** 根据新配置构建并替换模型适配快照；配置内容不应包含凭据，凭据由 SecretStore/环境覆盖解析。 */
    public synchronized void reload(Map<String, Map<String, String>> configuration) {
        LinkedHashMap<String, Map<String, String>> normalized = new LinkedHashMap<>();
        configuration.forEach(
                (provider, value) -> normalized.put(normalize(provider), Map.copyOf(value == null ? Map.of() : value)));
        LinkedHashMap<String, String> environment = new LinkedHashMap<>(processEnvironment);
        for (String provider : PROVIDERS) {
            applyConfiguration(environment, provider, normalized.getOrDefault(provider, Map.of()));
            secrets.resolve("provider:" + provider, "apiKey").ifPresent(value -> {
                try {
                    environment.put(apiKeyVariable(provider), new String(value));
                } finally {
                    Arrays.fill(value, '\0');
                }
            });
        }
        SpringAiCloudModelGateway replacement = SpringAiCloudModelGateway.fromEnvironment(environment);
        environment.replaceAll((key, value) -> key.endsWith("API_KEY") ? "" : value);
        SpringAiCloudModelGateway previous = delegate.getAndSet(replacement);
        providerConfiguration = Map.copyOf(normalized);
        if (previous != null) {
            previous.close();
        }
    }

    /** 返回当前模型目录及 configured 状态，不暴露底层 Provider 对象或 API Key。 */
    public List<CloudModelDescriptor> descriptors() {
        return current().descriptors();
    }

    /** 返回非敏感 Provider 配置快照，供版本化配置服务使用。 */
    public Map<String, Map<String, String>> configuration() {
        return providerConfiguration;
    }

    @Override
    public ModelResponse complete(ModelRequest request) throws Exception {
        return current().complete(request);
    }

    @Override
    public ModelResponse stream(ModelRequest request, ModelStreamSink sink) throws Exception {
        return current().stream(request, sink);
    }

    @Override
    public CompactionStrategy compactionStrategy(TurnConfig config) {
        return current().compactionStrategy(config);
    }

    @Override
    public NativeCompactionResult compact(ModelRequest request) throws Exception {
        return current().compact(request);
    }

    @Override
    public EmbeddingResult embed(String provider, String model, List<String> inputs) throws Exception {
        return current().embed(provider, model, inputs);
    }

    private SpringAiCloudModelGateway current() {
        SpringAiCloudModelGateway value = delegate.get();
        if (value == null) {
            throw new IllegalStateException("cloud model runtime is closed");
        }
        return value;
    }

    private static void applyConfiguration(
            Map<String, String> environment, String provider, Map<String, String> configuration) {
        String model = configuration.get("model");
        String baseUrl = configuration.get("baseUrl");
        if (model != null && !model.isBlank()) {
            environment.put(
                    switch (provider) {
                        case "openai" -> "JAVACLAW_OPENAI_MODEL";
                        case "anthropic" -> "JAVACLAW_ANTHROPIC_MODEL";
                        case "google" -> "JAVACLAW_GOOGLE_MODEL";
                        default -> throw new IllegalArgumentException("unsupported provider: " + provider);
                    },
                    model.strip());
        }
        if (baseUrl != null && !baseUrl.isBlank()) {
            environment.put(
                    switch (provider) {
                        case "openai" -> "OPENAI_BASE_URL";
                        case "anthropic" -> "ANTHROPIC_BASE_URL";
                        case "google" -> "GOOGLE_GENAI_BASE_URL";
                        default -> throw new IllegalArgumentException("unsupported provider: " + provider);
                    },
                    baseUrl.strip());
        }
        if ("openai".equals(provider) && configuration.containsKey("nativeCompaction")) {
            environment.put(
                    "JAVACLAW_OPENAI_NATIVE_COMPACTION",
                    Boolean.toString(Boolean.parseBoolean(configuration.get("nativeCompaction"))));
        }
    }

    private static String apiKeyVariable(String provider) {
        return switch (provider) {
            case "openai" -> "OPENAI_API_KEY";
            case "anthropic" -> "ANTHROPIC_API_KEY";
            case "google" -> "GOOGLE_API_KEY";
            default -> throw new IllegalArgumentException("unsupported provider: " + provider);
        };
    }

    private static String normalize(String provider) {
        String value = Objects.requireNonNull(provider, "provider").strip().toLowerCase(Locale.ROOT);
        if (!PROVIDERS.contains(value)) {
            throw new IllegalArgumentException("unsupported cloud provider: " + provider);
        }
        return value;
    }

    @Override
    public synchronized void close() {
        SpringAiCloudModelGateway current = delegate.getAndSet(null);
        if (current != null) {
            current.close();
        }
    }
}
