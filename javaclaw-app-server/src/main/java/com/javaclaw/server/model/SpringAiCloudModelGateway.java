package com.javaclaw.server.model;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

import com.anthropic.models.messages.Model;
import com.google.genai.Client;
import com.google.genai.types.HttpOptions;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.google.genai.GoogleGenAiChatModel;
import org.springframework.ai.google.genai.GoogleGenAiChatOptions;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import com.javaclaw.agent.model.CompactionStrategy;
import com.javaclaw.agent.model.EmbeddingGateway;
import com.javaclaw.agent.model.ModelGateway;
import com.javaclaw.agent.model.NativeCompactionResult;
import com.javaclaw.core.api.ModelMessage;
import com.javaclaw.core.api.ModelRequest;
import com.javaclaw.core.api.ModelResponse;
import com.javaclaw.core.api.ModelStreamSink;
import com.javaclaw.core.api.ModelToolCall;
import com.javaclaw.core.api.ModelUsage;
import com.javaclaw.core.api.ToolDescriptor;
import com.javaclaw.core.api.TurnConfig;

/** Provider-neutral gateway backed by Spring AI cloud adapters only. */
public final class SpringAiCloudModelGateway implements ModelGateway, EmbeddingGateway, AutoCloseable {
    private static final Duration DEFAULT_TIMEOUT = Duration.ofMinutes(3);
    private static final String COMPATIBILITY_API_KEY = "javaclaw-compatible-endpoint";

    private final Map<String, ChatModel> providers;
    private final Map<String, EmbeddingProvider> embeddingProviders;
    private final List<CloudModelDescriptor> descriptors;
    private final List<AutoCloseable> resources;
    private final OpenAiResponsesGateway openAiResponses;

    /** 固定 Provider 到 ChatModel 的适配表；调用使用领域请求/结果，不让 Spring AI 类型进入 Core。 */
    public SpringAiCloudModelGateway(Map<String, ChatModel> providers) {
        this(
                providers,
                providers.entrySet().stream()
                        .map(value -> new CloudModelDescriptor(value.getKey(), "", true, ""))
                        .toList(),
                Map.of(),
                List.of(),
                null);
    }

    private SpringAiCloudModelGateway(
            Map<String, ChatModel> providers,
            List<CloudModelDescriptor> descriptors,
            Map<String, EmbeddingProvider> embeddingProviders,
            List<AutoCloseable> resources,
            OpenAiResponsesGateway openAiResponses) {
        LinkedHashMap<String, ChatModel> normalized = new LinkedHashMap<>();
        providers.forEach((key, value) -> normalized.put(normalize(key), Objects.requireNonNull(value, "chat model")));
        this.providers = Map.copyOf(normalized);
        LinkedHashMap<String, EmbeddingProvider> normalizedEmbeddings = new LinkedHashMap<>();
        embeddingProviders.forEach((key, value) -> normalizedEmbeddings.put(normalize(key), value));
        this.embeddingProviders = Map.copyOf(normalizedEmbeddings);
        this.descriptors = List.copyOf(descriptors);
        this.resources = List.copyOf(resources);
        this.openAiResponses = openAiResponses;
    }

    /** 从当前进程环境创建云模型适配器；缺失凭据的 Provider 保持未配置，不落库。 */
    public static SpringAiCloudModelGateway fromEnvironment() {
        return fromEnvironment(System.getenv());
    }

    /** 从给定临时环境快照创建云模型适配器，便于隔离测试和进程装配；不会持久化环境凭据。 */
    public static SpringAiCloudModelGateway fromEnvironment(Map<String, String> environment) {
        Objects.requireNonNull(environment, "environment");
        LinkedHashMap<String, ChatModel> models = new LinkedHashMap<>();
        LinkedHashMap<String, EmbeddingProvider> embeddings = new LinkedHashMap<>();
        ArrayList<CloudModelDescriptor> descriptors = new ArrayList<>();
        ArrayList<AutoCloseable> resources = new ArrayList<>();
        try {
            OpenAiResponsesGateway openAiResponses = configureOpenAi(environment, embeddings, descriptors, resources);
            configureAnthropic(environment, models, descriptors, resources);
            configureGoogle(environment, models, embeddings, descriptors, resources);
            return new SpringAiCloudModelGateway(models, descriptors, embeddings, resources, openAiResponses);
        } catch (RuntimeException | LinkageError failure) {
            for (AutoCloseable resource : resources.reversed()) {
                try {
                    resource.close();
                } catch (Exception closing) {
                    failure.addSuppressed(closing);
                }
            }
            throw failure;
        }
    }

    /** 返回当前模型目录及 configured 状态，不暴露底层 Provider 对象或 API Key。 */
    public List<CloudModelDescriptor> descriptors() {
        return descriptors;
    }

    @Override
    public ModelResponse complete(ModelRequest request) throws Exception {
        String provider = normalize(request.config().provider());
        if ("openai".equals(provider) && openAiResponses != null) {
            return openAiResponses.complete(request);
        }
        ChatModel model = providers.get(provider);
        if (model == null) {
            throw new IllegalStateException("cloud provider is not configured: " + provider);
        }
        ChatResponse response = model.call(prompt(request, model));
        if (response == null || response.getResult() == null) {
            throw new IllegalStateException("cloud provider returned no generation");
        }
        AssistantMessage output = response.getResult().getOutput();
        String text = Objects.requireNonNullElse(output.getText(), "");
        String summary = reasoningSummary(output);
        List<ModelToolCall> calls = output.getToolCalls().stream()
                .map(call -> new ModelToolCall(call.id(), call.name(), call.arguments()))
                .toList();
        return new ModelResponse(text, summary, calls, usage(response));
    }

    @Override
    public ModelResponse stream(ModelRequest request, ModelStreamSink sink) throws Exception {
        Objects.requireNonNull(sink, "sink");
        String provider = normalize(request.config().provider());
        if ("openai".equals(provider) && openAiResponses != null) {
            return openAiResponses.stream(request, sink);
        }
        ChatModel model = providers.get(provider);
        if (model == null) {
            throw new IllegalStateException("cloud provider is not configured: " + provider);
        }
        StreamingAccumulator accumulator = new StreamingAccumulator(sink);
        model.stream(prompt(request, model)).doOnNext(accumulator::accept).blockLast(DEFAULT_TIMEOUT);
        return accumulator.result();
    }

    @Override
    public CompactionStrategy compactionStrategy(TurnConfig config) {
        return "openai".equals(normalize(config.provider())) && openAiResponses != null
                ? openAiResponses.compactionStrategy(config)
                : CompactionStrategy.SUMMARY;
    }

    @Override
    public NativeCompactionResult compact(ModelRequest request) throws Exception {
        if (!"openai".equals(normalize(request.config().provider())) || openAiResponses == null) {
            return ModelGateway.super.compact(request);
        }
        return openAiResponses.compact(request);
    }

    @Override
    public EmbeddingResult embed(String provider, String model, List<String> inputs) throws Exception {
        String normalized = normalize(provider);
        EmbeddingProvider adapter = embeddingProviders.get(normalized);
        if (adapter == null) {
            throw new IllegalStateException("embedding provider is not configured: " + normalized);
        }
        if (inputs == null || inputs.isEmpty()) {
            throw new IllegalArgumentException("embedding inputs must not be empty");
        }
        if (inputs.size() > 2_048 || inputs.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("embedding batch is invalid");
        }
        String selectedModel = Objects.requireNonNull(model, "model").strip();
        if (selectedModel.isEmpty()) {
            throw new IllegalArgumentException("embedding model is blank");
        }
        List<float[]> vectors = adapter.embed(selectedModel, List.copyOf(inputs));
        if (vectors.size() != inputs.size()) {
            throw new IllegalStateException("embedding provider returned a mismatched vector count");
        }
        int dimensions = vectors.isEmpty() ? 0 : vectors.getFirst().length;
        if (dimensions < 1 || vectors.stream().anyMatch(value -> value.length != dimensions)) {
            throw new IllegalStateException("embedding provider returned invalid dimensions");
        }
        return new EmbeddingResult(
                vectors,
                Map.of("provider", normalized, "model", selectedModel, "dimensions", Integer.toString(dimensions)));
    }

    private static Prompt prompt(ModelRequest request, ChatModel model) {
        List<Message> messages = request.messages().stream()
                .map(SpringAiCloudModelGateway::message)
                .toList();
        List<ToolCallback> tools = request.tools().stream()
                .map(DefinitionOnlyTool::new)
                .map(ToolCallback.class::cast)
                .toList();
        // Spring AI 2 不再把通用运行参数转换为 Provider Options；必须保留真实适配器类型和其初始化配置。
        // 每次复制再收窄工具/模型，不能修改共享默认 Options，也不能把 API Key 写入领域请求或 Prompt 档案。
        ToolCallingChatOptions.Builder<?> options = model.getOptions() instanceof ToolCallingChatOptions base
                ? base.mutate()
                : switch (normalize(request.config().provider())) {
                    case "openai" -> OpenAiChatOptions.builder().streamUsage(true);
                    case "anthropic" -> AnthropicChatOptions.builder().thinkingDisabled();
                    case "google" -> GoogleGenAiChatOptions.builder().thinkingBudget(0);
                    default -> throw new IllegalArgumentException("unsupported cloud provider");
                };
        options.model(request.config().model()).toolCallbacks(tools);
        integerAttribute(request, "maxOutputTokens").ifPresent(options::maxTokens);
        doubleAttribute(request, "temperature").ifPresent(options::temperature);
        return new Prompt(messages, options.build());
    }

    private static Message message(ModelMessage value) {
        return switch (value.role()) {
            case SYSTEM -> new SystemMessage(value.content());
            case USER ->
                UserMessage.builder()
                        .text(value.content())
                        .media(value.images().stream()
                                .map(image -> new org.springframework.ai.content.Media(
                                        org.springframework.util.MimeType.valueOf(image.mediaType()),
                                        new org.springframework.core.io.ByteArrayResource(image.bytes())))
                                .toList())
                        .build();
            case ASSISTANT ->
                AssistantMessage.builder()
                        .content(value.content())
                        .toolCalls(value.toolCalls().stream()
                                .map(call -> new AssistantMessage.ToolCall(
                                        call.id(), "function", call.name(), call.argumentsJson()))
                                .toList())
                        .build();
            case TOOL ->
                ToolResponseMessage.builder()
                        .responses(List.of(new ToolResponseMessage.ToolResponse(
                                value.toolCallId(), value.toolName(), value.content())))
                        .build();
        };
    }

    private static String reasoningSummary(AssistantMessage output) {
        Object value = output.getMetadata().get("reasoning_summary");
        return value == null ? "" : String.valueOf(value);
    }

    private static ModelUsage usage(ChatResponse response) {
        Usage usage =
                response.getMetadata() == null ? null : response.getMetadata().getUsage();
        if (usage == null) {
            return ModelUsage.ZERO;
        }
        return new ModelUsage(nonNegative(usage.getPromptTokens()), nonNegative(usage.getCompletionTokens()), 0);
    }

    private static long nonNegative(Number value) {
        return value == null ? 0 : Math.max(0, value.longValue());
    }

    private static java.util.Optional<Integer> integerAttribute(ModelRequest request, String key) {
        try {
            String value = request.config().attributes().get(key);
            return value == null
                    ? java.util.Optional.empty()
                    : java.util.Optional.of(Math.max(1, Integer.parseInt(value)));
        } catch (NumberFormatException ignored) {
            return java.util.Optional.empty();
        }
    }

    private static java.util.Optional<Double> doubleAttribute(ModelRequest request, String key) {
        try {
            String value = request.config().attributes().get(key);
            if (value == null) {
                return java.util.Optional.empty();
            }
            double parsed = Double.parseDouble(value);
            return Double.isFinite(parsed) ? java.util.Optional.of(parsed) : java.util.Optional.empty();
        } catch (NumberFormatException ignored) {
            return java.util.Optional.empty();
        }
    }

    private static OpenAiResponsesGateway configureOpenAi(
            Map<String, String> env,
            Map<String, EmbeddingProvider> embeddings,
            List<CloudModelDescriptor> descriptors,
            List<AutoCloseable> resources) {
        String configuredKey = value(env, "OPENAI_API_KEY");
        String defaultModel = fallback(value(env, "JAVACLAW_OPENAI_MODEL"), "gpt-5");
        String baseUrl = normalizeOpenAiBaseUrl(value(env, "OPENAI_BASE_URL"));
        // OpenAI SDK 要求提供非空 key；显式自定义端点可使用无秘密占位值，让 LM Studio 等免鉴权服务可用。
        // 官方端点仍必须有真实凭据，不能因为默认地址存在而被误判为已配置。
        String key = configuredKey == null && baseUrl != null && !isOfficialOpenAiEndpoint(baseUrl)
                ? COMPATIBILITY_API_KEY
                : configuredKey;
        descriptors.add(new CloudModelDescriptor("openai", defaultModel, key != null, "OPENAI_API_KEY"));
        if (key == null) {
            return null;
        }
        // 同步/异步对话及 Embedding 共享一个归本快照所有的 SDK Client；重载时统一释放连接池与线程。
        var client = org.springframework.ai.openai.setup.OpenAiSetup.setupSyncClient(
                baseUrl,
                key,
                null,
                null,
                null,
                null,
                false,
                false,
                defaultModel,
                DEFAULT_TIMEOUT,
                0,
                java.net.Proxy.NO_PROXY,
                Map.of(),
                ObservationRegistry.NOOP,
                null,
                List.of());
        resources.add(client::close);
        String embeddingBaseUrl = baseUrl;
        embeddings.put("openai", (model, inputs) -> {
            OpenAiEmbeddingOptions.Builder embeddingOptions = OpenAiEmbeddingOptions.builder()
                    .model(model)
                    .apiKey(key)
                    .timeout(DEFAULT_TIMEOUT)
                    .maxRetries(0);
            if (embeddingBaseUrl != null) {
                embeddingOptions.baseUrl(embeddingBaseUrl);
            }
            return OpenAiEmbeddingModel.builder()
                    .openAiClient(client)
                    .options(embeddingOptions.build())
                    .observationRegistry(ObservationRegistry.NOOP)
                    .build()
                    .embed(inputs);
        });
        boolean nativeCompaction = isOfficialOpenAiEndpoint(baseUrl)
                || Boolean.parseBoolean(
                        Objects.requireNonNullElse(env.get("JAVACLAW_OPENAI_NATIVE_COMPACTION"), "false"));
        return new OpenAiResponsesGateway(client, nativeCompaction);
    }

    private static String normalizeOpenAiBaseUrl(String baseUrl) {
        if (baseUrl == null) {
            return null;
        }
        URI endpoint = URI.create(baseUrl);
        String path = Objects.requireNonNullElse(endpoint.getPath(), "");
        return path.isEmpty() || "/".equals(path) ? endpoint.resolve("/v1").toString() : baseUrl;
    }

    private static boolean isOfficialOpenAiEndpoint(String baseUrl) {
        if (baseUrl == null) {
            return true;
        }
        try {
            URI endpoint = URI.create(baseUrl);
            String path = Objects.requireNonNullElse(endpoint.getPath(), "");
            return "https".equalsIgnoreCase(endpoint.getScheme())
                    && "api.openai.com".equalsIgnoreCase(endpoint.getHost())
                    && endpoint.getUserInfo() == null
                    && (endpoint.getPort() == -1 || endpoint.getPort() == 443)
                    && (path.isEmpty() || "/".equals(path) || "/v1".equals(path) || "/v1/".equals(path))
                    && endpoint.getRawQuery() == null
                    && endpoint.getRawFragment() == null;
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    private static void configureAnthropic(
            Map<String, String> env,
            Map<String, ChatModel> models,
            List<CloudModelDescriptor> descriptors,
            List<AutoCloseable> resources) {
        String key = value(env, "ANTHROPIC_API_KEY");
        String defaultModel = fallback(value(env, "JAVACLAW_ANTHROPIC_MODEL"), "claude-sonnet-4-20250514");
        descriptors.add(new CloudModelDescriptor("anthropic", defaultModel, key != null, "ANTHROPIC_API_KEY"));
        if (key == null) {
            return;
        }
        AnthropicChatOptions.Builder options = AnthropicChatOptions.builder()
                .model(Model.of(defaultModel))
                .apiKey(key)
                .timeout(DEFAULT_TIMEOUT)
                .maxRetries(0)
                .thinkingDisabled();
        String baseUrl = value(env, "ANTHROPIC_BASE_URL");
        if (baseUrl != null) {
            options.baseUrl(baseUrl);
        }
        var model = AnthropicChatModel.builder()
                .options(options.build())
                .observationRegistry(ObservationRegistry.NOOP)
                .build();
        resources.add(model.getAnthropicClient()::close);
        resources.add(model.getAnthropicClientAsync()::close);
        models.put("anthropic", model);
    }

    private static void configureGoogle(
            Map<String, String> env,
            Map<String, ChatModel> models,
            Map<String, EmbeddingProvider> embeddings,
            List<CloudModelDescriptor> descriptors,
            List<AutoCloseable> resources) {
        String key = fallback(value(env, "GOOGLE_API_KEY"), value(env, "GEMINI_API_KEY"));
        String defaultModel = fallback(value(env, "JAVACLAW_GOOGLE_MODEL"), "gemini-2.5-pro");
        descriptors.add(
                new CloudModelDescriptor("google", defaultModel, key != null, "GOOGLE_API_KEY or GEMINI_API_KEY"));
        if (key == null) {
            return;
        }
        Client.Builder client = Client.builder().apiKey(key);
        String baseUrl = value(env, "GOOGLE_GENAI_BASE_URL");
        if (baseUrl != null) {
            client.httpOptions(HttpOptions.builder()
                    .baseUrl(baseUrl)
                    .timeout(Math.toIntExact(DEFAULT_TIMEOUT.toMillis()))
                    .build());
        }
        Client created = client.build();
        if (created instanceof AutoCloseable closeable) {
            resources.add(closeable);
        }
        GoogleGenAiChatOptions options = GoogleGenAiChatOptions.builder()
                .model(defaultModel)
                .includeExtendedUsageMetadata(true)
                .thinkingBudget(0)
                .build();
        models.put(
                "google",
                GoogleGenAiChatModel.builder()
                        .genAiClient(created)
                        .options(options)
                        .observationRegistry(ObservationRegistry.NOOP)
                        .build());
        embeddings.put(
                "google",
                (model, inputs) -> created
                        .models
                        .embedContent(
                                model,
                                inputs,
                                com.google.genai.types.EmbedContentConfig.builder()
                                        .build())
                        .embeddings()
                        .orElseThrow(() -> new IllegalStateException("Google returned no embeddings"))
                        .stream()
                        .map(value -> {
                            List<Float> floats = value.values()
                                    .orElseThrow(() -> new IllegalStateException("Google returned an empty embedding"));
                            float[] vector = new float[floats.size()];
                            for (int index = 0; index < floats.size(); index++) {
                                vector[index] = floats.get(index);
                            }
                            return vector;
                        })
                        .toList());
    }

    private static String normalize(String value) {
        String normalized = Objects.requireNonNull(value, "provider").strip().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "gemini", "google-genai" -> "google";
            default -> normalized;
        };
    }

    private static String value(Map<String, String> env, String key) {
        String value = env.get(key);
        return value == null || value.isBlank() ? null : value.strip();
    }

    private static String fallback(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    @Override
    public void close() {
        RuntimeException owner = null;
        for (int index = resources.size() - 1; index >= 0; index--) {
            try {
                resources.get(index).close();
            } catch (Exception failure) {
                if (owner == null) {
                    owner = new IllegalStateException("failed to close cloud provider resources");
                }
                owner.addSuppressed(failure);
            }
        }
        if (owner != null) {
            throw owner;
        }
    }

    private record DefinitionOnlyTool(ToolDescriptor descriptor) implements ToolCallback {
        private DefinitionOnlyTool {
            Objects.requireNonNull(descriptor, "descriptor");
        }

        @Override
        public ToolDefinition getToolDefinition() {
            return ToolDefinition.builder()
                    .name(descriptor.name())
                    .description(descriptor.description())
                    .inputSchema(descriptor.inputSchemaJson())
                    .build();
        }

        @Override
        public String call(String toolInput) {
            throw new IllegalStateException("tool execution belongs to the JavaClaw Agent Kernel");
        }
    }

    @FunctionalInterface
    private interface EmbeddingProvider {
        List<float[]> embed(String model, List<String> inputs) throws Exception;
    }

    private static final class StreamingAccumulator {
        private final ModelStreamSink sink;
        private final StringBuilder text = new StringBuilder();
        private final StringBuilder reasoning = new StringBuilder();
        private final LinkedHashMap<String, ModelToolCall> calls = new LinkedHashMap<>();
        private ModelUsage usage = ModelUsage.ZERO;
        private boolean observed;

        private StreamingAccumulator(ModelStreamSink sink) {
            this.sink = sink;
        }

        private void accept(ChatResponse response) {
            if (response == null || response.getResult() == null) {
                return;
            }
            observed = true;
            AssistantMessage output = response.getResult().getOutput();
            String fragment = Objects.requireNonNullElse(output.getText(), "");
            if (!fragment.isEmpty()) {
                text.append(fragment);
                sink.text(fragment);
            }
            String reasoningFragment = reasoningSummary(output);
            if (!reasoningFragment.isEmpty()) {
                reasoning.append(reasoningFragment);
                sink.reasoningSummary(reasoningFragment);
            }
            output.getToolCalls().forEach(call -> {
                String id = call.id() == null || call.id().isBlank() ? call.name() + "#" + calls.size() : call.id();
                ModelToolCall previous = calls.get(id);
                String arguments = merge(
                        previous == null ? "" : previous.argumentsJson(),
                        Objects.requireNonNullElse(call.arguments(), ""));
                calls.put(id, new ModelToolCall(id, call.name(), arguments));
            });
            ModelUsage current = SpringAiCloudModelGateway.usage(response);
            if (!ModelUsage.ZERO.equals(current)) {
                usage = current;
                sink.usage(current);
            }
        }

        private ModelResponse result() {
            if (!observed) {
                throw new IllegalStateException("cloud provider returned no generation");
            }
            return new ModelResponse(text.toString(), reasoning.toString(), List.copyOf(calls.values()), usage);
        }

        private static String merge(String previous, String fragment) {
            if (fragment.isEmpty()) {
                return previous;
            }
            if (!previous.isEmpty() && fragment.startsWith(previous)) {
                return fragment;
            }
            if (previous.endsWith(fragment)) {
                return previous;
            }
            return previous + fragment;
        }
    }
}
