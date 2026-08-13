package com.javaclaw.framework.springai;

import com.google.genai.Client;
import com.google.genai.types.HttpOptions;
import com.javaclaw.config.AgentConfig;
import com.javaclaw.framework.spi.ModelTier;
import com.javaclaw.framework.api.ModelPolicyRefs;
import com.javaclaw.framework.spi.EmbeddingModelProvider;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.google.genai.GoogleGenAiChatModel;
import org.springframework.ai.google.genai.GoogleGenAiChatOptions;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** The only workspace adapter allowed to construct provider ChatModel instances. */
public final class SpringAiModelFactory implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(SpringAiModelFactory.class);
    private final AgentConfig config;
    private final ObservationRegistry observations;
    private final List<AutoCloseable> resources = new ArrayList<>();
    private SpringAiModelRegistry installedRegistry;
    private final Map<String, ChatModel> installedModels = new LinkedHashMap<>();
    private SpringAiModelRegistry.Registration installedRegistration;

    public SpringAiModelFactory(AgentConfig config, ObservationRegistry observations) {
        this.config = Objects.requireNonNull(config, "config");
        this.observations = Objects.requireNonNull(observations, "observations");
    }

    public ModelPolicyRefs install(String workspaceId, SpringAiModelRegistry registry) {
        if (installedRegistration != null) {
            throw new IllegalStateException("model factory is already installed");
        }
        int resourceStart = resources.size();
        String prefix = "workspace:" + workspaceId + ":";
        ModelPolicyRefs refs = new ModelPolicyRefs(
                prefix + "high", prefix + "normal", prefix + "light");
        try {
            ChatModel high = create(new TierSpec(
                    config.getProviderType(), config.getBaseUrl(), config.getModelName(),
                    config.getApiKey(), config.isThinkingEnabled()));
            ChatModel normal = create(new TierSpec(
                    config.getNormalProviderType(), config.getNormalBaseUrl(),
                    config.getNormalModelName(), config.getNormalApiKey(),
                    config.isNormalThinkingEnabled()));
            ChatModel light = create(new TierSpec(
                    config.getLightProviderType(), config.getLightBaseUrl(),
                    config.getLightModelName(), config.getLightApiKey(), false));
            Map<String, ChatModel> generation = Map.of(
                    refs.high(), high, refs.normal(), normal, refs.light(), light);
            SpringAiModelRegistry.Registration registration = registry.installWorkspace(
                    workspaceId, generation, Map.of(
                            ModelTier.HIGH, refs.high(),
                            ModelTier.NORMAL, refs.normal(),
                            ModelTier.LIGHT, refs.light()));
            installedRegistry = registry;
            installedModels.putAll(generation);
            installedRegistration = registration;
            return refs;
        } catch (RuntimeException failure) {
            closeResourcesFrom(resourceStart, failure);
            throw failure;
        }
    }

    public EmbeddingModelProvider createEmbeddingProvider() {
        if (!config.isRagEnabled() || config.getRagEmbeddingModelName() == null
                || config.getRagEmbeddingModelName().isBlank()) {
            return embeddingProvider(false, null, null);
        }
        try {
            OpenAiEmbeddingOptions options = OpenAiEmbeddingOptions.builder()
                    .model(config.getRagEmbeddingModelName())
                    .dimensions(config.getRagEmbeddingDimensions())
                    .apiKey(apiKey(config.getRagEmbeddingApiKey()))
                    .baseUrl(config.getRagEmbeddingBaseUrl())
                    .timeout(Duration.ofSeconds(config.getModelRequestTimeoutSeconds()))
                    .maxRetries(3)
                    .build();
            return embeddingProvider(true,
                    OpenAiEmbeddingModel.builder().options(options)
                            .observationRegistry(observations).build(), null);
        } catch (RuntimeException failure) {
            String message = failure.getMessage() == null
                    ? failure.getClass().getSimpleName() : failure.getMessage();
            return embeddingProvider(true, null, "嵌入模型创建失败: " + message);
        }
    }

    private ChatModel create(TierSpec spec) {
        String provider = spec.provider() == null ? "openai"
                : spec.provider().trim().toLowerCase(Locale.ROOT);
        return switch (provider) {
            case "anthropic" -> anthropic(spec);
            case "gemini", "google", "google-genai" -> google(spec);
            case "ollama" -> ollama(spec);
            case "dashscope", "openai" -> openAi(spec);
            default -> openAi(spec);
        };
    }

    private ChatModel openAi(TierSpec spec) {
        OpenAiChatOptions.Builder builder = OpenAiChatOptions.builder();
        builder.model(spec.model())
                .apiKey(apiKey(spec.apiKey()))
                .baseUrl(spec.baseUrl())
                .timeout(Duration.ofSeconds(config.getModelRequestTimeoutSeconds()))
                .maxRetries(3)
                .streamUsage(true);
        if (spec.thinking()) {
            builder.reasoningEffort("medium");
        } else if (isDashScope(spec)) {
            builder.extraBody(java.util.Map.of("enable_thinking", false));
        }
        return OpenAiChatModel.builder().options(builder.build())
                .observationRegistry(observations).build();
    }

    private ChatModel anthropic(TierSpec spec) {
        AnthropicChatOptions.Builder builder = AnthropicChatOptions.builder();
        builder.model(com.anthropic.models.messages.Model.of(spec.model()))
                .apiKey(apiKey(spec.apiKey()))
                .baseUrl(spec.baseUrl())
                .timeout(Duration.ofSeconds(config.getModelRequestTimeoutSeconds()))
                .maxRetries(3);
        if (spec.thinking()) builder.thinkingEnabled(config.getThinkingBudget());
        else builder.thinkingDisabled();
        return AnthropicChatModel.builder().options(builder.build())
                .observationRegistry(observations).build();
    }

    private ChatModel google(TierSpec spec) {
        Client.Builder clientBuilder = Client.builder().apiKey(apiKey(spec.apiKey()));
        if (spec.baseUrl() != null && !spec.baseUrl().isBlank()) {
            clientBuilder.httpOptions(HttpOptions.builder()
                    .baseUrl(spec.baseUrl())
                    .timeout(Math.toIntExact(Duration.ofSeconds(
                            config.getModelRequestTimeoutSeconds()).toMillis()))
                    .build());
        }
        Client client = clientBuilder.build();
        if (client instanceof AutoCloseable closeable) resources.add(closeable);
        GoogleGenAiChatOptions.Builder options = GoogleGenAiChatOptions.builder();
        options.model(spec.model()).includeExtendedUsageMetadata(true);
        if (spec.thinking()) options.thinkingBudget(config.getThinkingBudget());
        else options.thinkingBudget(0);
        return GoogleGenAiChatModel.builder().genAiClient(client).options(options.build())
                .observationRegistry(observations).build();
    }

    private ChatModel ollama(TierSpec spec) {
        OllamaChatOptions.Builder options = OllamaChatOptions.builder();
        options.model(spec.model());
        if (spec.thinking()) options.enableThinking();
        else options.disableThinking();
        return OllamaChatModel.builder()
                .ollamaApi(OllamaApi.builder().baseUrl(spec.baseUrl()).build())
                .options(options.build()).observationRegistry(observations).build();
    }

    private static boolean isDashScope(TierSpec spec) {
        String provider = spec.provider() == null ? "" : spec.provider().toLowerCase(Locale.ROOT);
        String url = spec.baseUrl() == null ? "" : spec.baseUrl().toLowerCase(Locale.ROOT);
        return provider.equals("dashscope") || url.contains("dashscope") || url.contains("aliyuncs");
    }

    private static String apiKey(String value) {
        return value == null || value.isBlank() ? "not-needed" : value;
    }

    @Override
    public void close() {
        if (installedRegistration != null) {
            installedRegistration.close();
            installedRegistration = null;
            installedModels.clear();
            installedRegistry = null;
        }
        for (int i = resources.size() - 1; i >= 0; i--) {
            try { resources.get(i).close(); }
            catch (Exception failure) {
                log.debug("关闭 Spring AI provider 资源失败", failure);
            }
        }
        resources.clear();
    }

    private void closeResourcesFrom(int first, RuntimeException owner) {
        for (int index = resources.size() - 1; index >= first; index--) {
            AutoCloseable resource = resources.remove(index);
            try {
                resource.close();
            } catch (Exception closeFailure) {
                owner.addSuppressed(closeFailure);
            }
        }
    }

    private record TierSpec(
            String provider, String baseUrl, String model, String apiKey, boolean thinking) {}

    private static EmbeddingModelProvider embeddingProvider(
            boolean configured, EmbeddingModel model, String error) {
        return new EmbeddingModelProvider() {
            @Override public boolean configured() { return configured; }
            @Override public String initializationError() { return error; }
            @Override
            public double[] embed(String text, Duration timeout) {
                if (model == null) throw new IllegalStateException(
                        error == null ? "embedding model is not configured" : error);
                float[] values = model.embed(text);
                if (values == null) return null;
                double[] converted = new double[values.length];
                for (int index = 0; index < values.length; index++) converted[index] = values[index];
                return converted;
            }
        };
    }
}
