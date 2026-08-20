package com.javaclaw.plugins.deliverance;

import com.codahale.metrics.MetricRegistry;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.javaclaw.service.api.CancellationToken;
import io.teknek.deliverance.DType;
import io.teknek.deliverance.embedding.PoolingType;
import io.teknek.deliverance.generator.GeneratorParameters;
import io.teknek.deliverance.generator.Response;
import io.teknek.deliverance.grace.AutoTokenizer;
import io.teknek.deliverance.grace.PreTrainedTokenizer;
import io.teknek.deliverance.math.VectorMathUtils;
import io.teknek.deliverance.math.WrappedForkJoinPool;
import io.teknek.deliverance.model.AbstractModel;
import io.teknek.deliverance.model.AutoModelForCausaLm;
import io.teknek.deliverance.model.CausalLanguageModel;
import io.teknek.deliverance.model.ModelType;
import io.teknek.deliverance.model.ModelSupport;
import io.teknek.deliverance.model.tensorparallel.SingleRankTensorParallelCollectives;
import io.teknek.deliverance.model.tensorparallel.StaticTensorParallelContext;
import io.teknek.deliverance.safetensors.Config;
import io.teknek.deliverance.safetensors.DefaultWeightLoader;
import io.teknek.deliverance.safetensors.WeightLoader;
import io.teknek.deliverance.safetensors.fetch.ModelFetcher;
import io.teknek.deliverance.safetensors.prompt.Function;
import io.teknek.deliverance.safetensors.prompt.PromptContext;
import io.teknek.deliverance.safetensors.prompt.PromptSupport;
import io.teknek.deliverance.safetensors.prompt.Tool;
import io.teknek.deliverance.safetensors.prompt.ToolResult;
import io.teknek.deliverance.tensor.ArrayQueueTensorAllocator;
import io.teknek.deliverance.tensor.AbstractTensor;
import io.teknek.deliverance.tensor.KvBufferCacheSettings;
import io.teknek.deliverance.tensor.TensorAllocator;
import io.teknek.deliverance.tensor.operations.ConfigurableTensorProvider;
import io.teknek.deliverance.tensor.operations.MachineSpec;
import io.teknek.deliverance.tensor.operations.PanamaTensorOperations;
import io.teknek.deliverance.toolcallparser.DefaultToolCallParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/** Deliverance 0.0.12 adapter. No class in the JavaClaw Host imports this package or dependency. */
final class DeliveranceEngine implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(DeliveranceEngine.class);
    private static final Set<String> GENERATION_PARAMETERS = Set.of(
            "temperature", "maxTokens", "seed", "stop", "includeStopString",
            "guidedChoice", "guidedRegex", "guidedJson", "logprobs", "topLogprobs",
            "xtcThreshold", "xtcProbability", "topK", "topP", "uniformTopP",
            "cacheSalt", "enableThinking");
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() { };

    private final Protocol.StartupConfig config;
    private final ObjectMapper json;
    private final CausalLanguageModel generationModel;
    private final AbstractModel embeddingModel;
    private final float[] embeddingFinalNorm;
    private final String modelFamily;
    private final String tensorBackend;
    private final Backend backend;

    DeliveranceEngine(Protocol.StartupConfig config, ObjectMapper json) throws Exception {
        this.config = validate(config);
        this.json = json;
        Path modelPath = Path.of(config.modelPath()).toRealPath(LinkOption.NOFOLLOW_LINKS);
        modelFamily = detectFamily(modelPath, json);
        LocalModelFetcher fetcher = new LocalModelFetcher(modelPath, modelFamily);
        if ("EMBEDDING".equalsIgnoreCase(config.modelKind())) {
            generationModel = null;
            LoadedBackend<LoadedEmbedding> loaded = loadWithBackendFallback(selected -> loadEmbedding(
                    modelPath, loadDType("workingMemoryType", DType.F32),
                    loadDType("workingQuantType", DType.I8), selected));
            embeddingModel = loaded.model().model();
            embeddingFinalNorm = loaded.model().finalNorm();
            tensorBackend = loaded.backend().name();
            backend = loaded.backend();
        } else {
            embeddingModel = null;
            embeddingFinalNorm = null;
            LoadedBackend<CausalLanguageModel> loaded = loadWithBackendFallback(
                    selected -> loadGeneration(fetcher, selected));
            generationModel = loaded.model();
            tensorBackend = loaded.backend().name();
            backend = loaded.backend();
        }
        if (!config.probeMode()) {
            if (config.contextLength() > actualContextLength()) {
                close();
                throw new IllegalArgumentException("档案上下文上限超过模型实际能力");
            }
            if (embeddingModel != null
                    && config.embeddingDimensions() != actualEmbeddingDimensions()) {
                close();
                throw new IllegalArgumentException("档案嵌入维度与模型实际能力不一致");
            }
        }
    }

    Set<String> capabilities() {
        Set<String> base = generationModel == null
                ? Set.of("embeddings", "exact_usage", "terminal_usage", "cancellation")
                : Set.of("chat", "streaming", "reasoning", "tools", "exact_usage",
                        "terminal_usage", "cancellation");
        java.util.HashSet<String> values = new java.util.HashSet<>(base);
        values.add("backend:" + tensorBackend);
        return Set.copyOf(values);
    }

    int actualContextLength() {
        return generationModel == null
                ? embeddingModel.getConfig().contextLength : generationModel.getConfig().contextLength;
    }

    int actualEmbeddingDimensions() {
        return embeddingModel == null ? 0 : embeddingModel.getConfig().embeddingLength;
    }

    String selectedBackend() { return tensorBackend; }

    Protocol.ChatResponse chat(
            Protocol.ChatRequest request,
            CancellationToken cancellation,
            Consumer<Protocol.StreamEvent> stream,
            long queueTimeMs) {
        if (generationModel == null) throw new UnsupportedOperationException("当前档案不是生成模型");
        long started = System.nanoTime();
        UsageTracker usage = new UsageTracker(0);
        try {
            rejectUnknown(request.parameters());
            validateToolChoice(request);
            PromptContext prompt = prompt(request);
            int promptTokens = generationModel.getTokenizer().encode(prompt.getPrompt()).length();
            usage.addPromptTokens(promptTokens);
            int requestedMax = number(merged(request.parameters()).get("maxTokens"), 256).intValue();
            int contextLimit = config.contextLength() > 0
                    ? Math.min(config.contextLength(), actualContextLength()) : actualContextLength();
            if (promptTokens + requestedMax > contextLimit) {
                throw new IllegalArgumentException("请求超过模型上下文上限");
            }
            GeneratorParameters parameters = generatorParameters(merged(request.parameters()));
            ReasoningStreamParser parser = new ReasoningStreamParser(
                    delta -> emitDelta(request.requestId(), "CONTENT_DELTA", delta, stream, cancellation),
                    delta -> emitDelta(request.requestId(), "REASONING_DELTA", delta, stream, cancellation));
            requireActive(cancellation);
            Response response = generationModel.generate(
                    UUID.fromString(normalizeUuid(request.requestId())), prompt, parameters,
                    (token, raw, cleaned, timing) -> {
                        usage.generatedToken();
                        requireActive(cancellation);
                        if (stream != null && raw != null && !raw.isEmpty()) parser.accept(raw);
                    });
            parser.finish();
            requireActive(cancellation);
            List<Protocol.ToolCall> toolCalls = response.toolCalls == null ? List.of()
                    : response.toolCalls.stream().map(call -> new Protocol.ToolCall(
                            call.getId() == null ? "call_" + UUID.randomUUID() : call.getId(),
                            call.getName(), write(call.getParameters()))).toList();
            Protocol.Usage exact = new Protocol.Usage(
                    response.promptTokens, response.generatedTokens.size());
            return new Protocol.ChatResponse(request.requestId(), config.modelName(),
                    nullable(response.responseText), nullable(response.reasoning), toolCalls,
                    finishReason(response), exact, Math.max(0, queueTimeMs),
                    Duration.ofNanos(System.nanoTime() - started).toMillis());
        } catch (CountedInferenceFailure failure) {
            throw failure;
        } catch (Throwable failure) {
            throw new CountedInferenceFailure(failure, usage.snapshot(),
                    failure instanceof CancellationException);
        }
    }

    Protocol.EmbeddingResponse embeddings(
            Protocol.EmbeddingRequest request, CancellationToken cancellation) {
        if (embeddingModel == null) throw new UnsupportedOperationException("当前档案不是嵌入模型");
        if (request.input().isEmpty()) throw new IllegalArgumentException("嵌入输入不能为空");
        long started = System.nanoTime();
        List<float[]> values = new ArrayList<>(request.input().size());
        UsageTracker usage = new UsageTracker(0);
        String pooling = resolvedPooling(modelFamily, config.loadParameters().get("pooling"));
        try {
            for (String input : request.input()) {
                requireActive(cancellation);
                String normalized = input == null ? "" : input;
                usage.addPromptTokens(embeddingModel.getTokenizer().encode(normalized).length());
                float[] value = embed(normalized, pooling);
                requireActive(cancellation);
                if (config.embeddingDimensions() > 0
                        && value.length != config.embeddingDimensions()) {
                    throw new IllegalStateException("实际嵌入维度与已验证档案不一致");
                }
                values.add(value);
            }
            int dimensions = values.isEmpty() ? actualEmbeddingDimensions() : values.getFirst().length;
            return new Protocol.EmbeddingResponse(request.requestId(), config.modelName(),
                    dimensions, values, usage.snapshot(),
                    Duration.ofNanos(System.nanoTime() - started).toMillis());
        } catch (CountedInferenceFailure failure) {
            throw failure;
        } catch (Throwable failure) {
            throw new CountedInferenceFailure(failure, usage.snapshot(),
                    failure instanceof CancellationException);
        }
    }

    private PromptContext prompt(Protocol.ChatRequest request) {
        PromptSupport.Builder builder = generationModel.promptSupport()
                .orElseThrow(() -> new UnsupportedOperationException("模型没有可用 chat template"))
                .builder();
        Map<String, String> callNames = new HashMap<>();
        for (Protocol.Message message : request.messages()) {
            switch (message.role().toUpperCase(Locale.ROOT)) {
                case "SYSTEM" -> builder.addSystemMessage(message.content());
                case "USER" -> builder.addUserMessage(message.content());
                case "ASSISTANT" -> {
                    builder.addAssistantMessage(message.content());
                    for (Protocol.ToolCall call : message.toolCalls()) {
                        callNames.put(call.id(), call.name());
                        builder.addToolCall(new io.teknek.deliverance.safetensors.prompt.ToolCall(
                                call.name(), call.id(), readMap(call.argumentsJson())));
                    }
                }
                case "TOOL" -> builder.addToolResult(ToolResult.from(
                        callNames.getOrDefault(message.toolCallId(), "tool"),
                        message.toolCallId(), message.content()));
                default -> throw new IllegalArgumentException("不支持的消息角色: " + message.role());
            }
        }
        if (!"NONE".equalsIgnoreCase(request.toolChoice().mode())) {
            for (Protocol.Tool tool : request.tools()) builder.addToolItem(toDeliveranceTool(tool));
        }
        Object thinking = merged(request.parameters()).get("enableThinking");
        if (thinking instanceof Boolean enabled) builder.addTemplateArg("enable_thinking", enabled);
        return builder.build();
    }

    @SuppressWarnings("unchecked")
    private Tool toDeliveranceTool(Protocol.Tool tool) {
        Function.Builder function = Function.builder().name(tool.name()).description(tool.description());
        Object rawProperties = tool.inputSchema().get("properties");
        Set<String> required = tool.inputSchema().get("required") instanceof List<?> list
                ? list.stream().map(String::valueOf).collect(java.util.stream.Collectors.toSet()) : Set.of();
        if (rawProperties instanceof Map<?, ?> properties) {
            for (Map.Entry<?, ?> entry : properties.entrySet()) {
                if (!(entry.getValue() instanceof Map<?, ?> details)) continue;
                function.addParameter(String.valueOf(entry.getKey()),
                        (Map<String, Object>) new LinkedHashMap<>(details),
                        required.contains(String.valueOf(entry.getKey())));
            }
        }
        return Tool.from(function.build());
    }

    private GeneratorParameters generatorParameters(Map<String, Object> values) {
        GeneratorParameters p = new GeneratorParameters();
        optionalNumber(values, "temperature").ifPresent(v -> p.withTemperature(v.floatValue()));
        optionalNumber(values, "maxTokens").ifPresent(v -> p.withMaxTokens(v.intValue()));
        optionalNumber(values, "seed").ifPresent(v -> p.withSeed(v.intValue()));
        optionalStrings(values, "stop").ifPresent(p::withStopWords);
        optionalBoolean(values, "includeStopString").ifPresent(p::withIncludeStopStrInOutput);
        optionalStrings(values, "guidedChoice").ifPresent(p::withGuidedChoice);
        optionalString(values, "guidedRegex").ifPresent(p::withGuidedRegex);
        optionalString(values, "guidedJson").ifPresent(p::withGuidedJson);
        optionalBoolean(values, "logprobs").ifPresent(p::withLogProbs);
        optionalNumber(values, "topLogprobs").ifPresent(v -> p.withTopLogProbs(v.intValue()));
        optionalNumber(values, "xtcThreshold").ifPresent(v -> p.withXtcThreshold(v.floatValue()));
        optionalNumber(values, "xtcProbability").ifPresent(v -> p.withXtcProbability(v.floatValue()));
        optionalNumber(values, "topK").ifPresent(v -> p.withTopK(v.floatValue()));
        optionalNumber(values, "topP").ifPresent(v -> p.withTopP(v.floatValue()));
        optionalNumber(values, "uniformTopP").ifPresent(v -> p.withUniformTopP(v.floatValue()));
        optionalString(values, "cacheSalt").ifPresent(p::withCacheSalt);
        return p;
    }

    private void validateToolChoice(Protocol.ChatRequest request) {
        String mode = request.toolChoice().mode().toUpperCase(Locale.ROOT);
        if (!Set.of("AUTO", "NONE", "REQUIRED", "NAMED").contains(mode)) {
            throw new IllegalArgumentException("未知工具选择模式: " + mode);
        }
        if ("REQUIRED".equals(mode) || "NAMED".equals(mode)) {
            throw new IllegalArgumentException("当前 Deliverance 运行时不支持强制工具选择");
        }
        if (!request.parallelToolCalls() && !request.tools().isEmpty()) {
            throw new IllegalArgumentException("当前 Deliverance 运行时不支持限制并行工具调用");
        }
    }

    private Map<String, Object> merged(Map<String, Object> request) {
        Map<String, Object> result = new LinkedHashMap<>(config.defaultParameters());
        result.putAll(request);
        return result;
    }

    private void rejectUnknown(Map<String, Object> parameters) {
        for (String key : parameters.keySet()) {
            if (!GENERATION_PARAMETERS.contains(key)) throw new IllegalArgumentException("未知生成参数: " + key);
        }
    }

    private DType loadDType(String name, DType fallback) {
        return optionalDType(name).orElse(fallback);
    }

    private Optional<DType> optionalDType(String name) {
        Object raw = config.loadParameters().get(name);
        if (raw == null || String.valueOf(raw).isBlank()) return Optional.empty();
        return Optional.of(DType.valueOf(String.valueOf(raw).toUpperCase(Locale.ROOT)));
    }

    static String resolvedPooling(String family, Object configured) {
        String requested = configured == null || String.valueOf(configured).isBlank()
                ? "AUTO" : String.valueOf(configured).strip().toUpperCase(Locale.ROOT);
        if ("AUTO".equals(requested)) {
            return "qwen3".equalsIgnoreCase(family) ? "LAST" : "AVG";
        }
        if (!Set.of("LAST", "AVG", "MAX", "SUM", "MODEL").contains(requested)) {
            throw new IllegalArgumentException("未知嵌入池化方式: " + requested);
        }
        return requested;
    }

    private float[] embed(String input, String pooling) {
        if (!"LAST".equals(pooling)) {
            return embeddingModel.embed(input, PoolingType.valueOf(pooling));
        }
        int[] tokenIds = embeddingModel.getTokenizer().encode(input).inputIds();
        if (tokenIds.length == 0) {
            throw new IllegalArgumentException("嵌入输入分词后不能为空");
        }
        if (tokenIds.length >= actualContextLength()) {
            throw new IllegalArgumentException("嵌入输入超过模型上下文上限");
        }
        try (AbstractTensor output = embeddingModel.batchForward(tokenIds, 0)) {
            if (output == null || output.shape().first() < 1) {
                throw new IllegalStateException("模型未返回可用的末尾 Token 隐藏状态");
            }
            AbstractTensor lastToken = output.slice(output.shape().first() - 1);
            float[] result = new float[actualEmbeddingDimensions()];
            for (int index = 0; index < result.length; index++) {
                result[index] = lastToken.get(0, index);
            }
            applyEmbeddingFinalNorm(result);
            VectorMathUtils.l2normalize(result);
            for (float value : result) {
                if (!Float.isFinite(value)) {
                    throw new IllegalStateException("模型返回了无效的嵌入向量");
                }
            }
            return result;
        }
    }

    private void applyEmbeddingFinalNorm(float[] value) {
        if (embeddingFinalNorm == null) return;
        double squares = 0;
        for (float item : value) squares += item * item;
        double scale = 1.0 / Math.sqrt(squares / value.length
                + embeddingModel.getConfig().layerNormEps);
        for (int index = 0; index < value.length; index++) {
            value[index] = (float) (value[index] * scale * embeddingFinalNorm[index]);
        }
    }

    private CausalLanguageModel loadGeneration(LocalModelFetcher fetcher, Backend backend) {
        AutoModelForCausaLm.Builder builder = AutoModelForCausaLm.newBuilder(fetcher).withDownload(false)
                .withMetricRegistry(backend.metrics()).withTensorAllocator(backend.allocator())
                .withWrappedForkJoinPool(backend.pool()).withTensorProvider(backend.provider())
                .withKvBufferCacheSettings(kvCache());
        optionalDType("workingMemoryType").ifPresent(builder::withWorkingMemoryType);
        optionalDType("workingQuantType").ifPresent(builder::withWorkingQuantType);
        optionalDType("outputHeadQuantization").ifPresent(builder::withOutputHeadQuantization);
        integer(config.loadParameters().get("maxBatchSize")).ifPresent(builder::withMaxBatchSize);
        return builder.build();
    }

    private LoadedEmbedding loadEmbedding(Path modelPath, DType memory, DType quantized, Backend backend) {
        File configFile = modelPath.resolve("config.json").toFile();
        ModelType modelType = ModelSupport.detectModel(configFile);
        WeightLoader weights = new DefaultWeightLoader(modelPath.toFile());
        if ("qwen3".equalsIgnoreCase(modelFamily)) {
            weights = new ModelPrefixWeightLoader(weights);
        }
        AbstractModel model = null;
        try {
            Config modelConfig = io.teknek.deliverance.JsonUtils.om.readValue(
                    configFile, modelType.getConfigClass());
            PreTrainedTokenizer tokenizer = AutoTokenizer.fromPretrained(modelPath);
            var constructor = modelType.getModelClass().getConstructor(
                    AbstractModel.InferenceType.class, Config.class, WeightLoader.class,
                    PreTrainedTokenizer.class, DType.class, DType.class, Optional.class,
                    ConfigurableTensorProvider.class, MetricRegistry.class, TensorAllocator.class,
                    KvBufferCacheSettings.class,
                    io.teknek.deliverance.toolcallparser.ToolCallParser.class,
                    WrappedForkJoinPool.class,
                    io.teknek.deliverance.model.tensorparallel.TensorParallelContext.class,
                    io.teknek.deliverance.model.tensorparallel.TensorParallelCollectives.class,
                    Optional.class);
            model = constructor.newInstance(AbstractModel.InferenceType.FULL_EMBEDDING,
                    modelConfig, weights, tokenizer, memory, quantized, Optional.empty(),
                    backend.provider(), backend.metrics(), backend.allocator(), kvCache(),
                    new DefaultToolCallParser(), backend.pool(),
                    new StaticTensorParallelContext(0, 1),
                    new SingleRankTensorParallelCollectives(), Optional.empty());
            float[] finalNorm = "qwen3".equalsIgnoreCase(modelFamily)
                    ? loadFinalNorm(weights, modelConfig.embeddingLength) : null;
            return new LoadedEmbedding(model, finalNorm);
        } catch (Exception failure) {
            closeFailedEmbedding(model, weights, failure);
            throw new IllegalStateException("无法加载嵌入模型: " + specificMessage(failure), failure);
        }
    }

    private static float[] loadFinalNorm(WeightLoader weights, int dimensions) {
        try (AbstractTensor tensor = weights.load("model.norm.weight")) {
            float[] result = new float[dimensions];
            for (int index = 0; index < dimensions; index++) result[index] = tensor.get(0, index);
            return result;
        }
    }

    private static void closeFailedEmbedding(
            AbstractModel model, WeightLoader weights, Exception failure) {
        try {
            if (model != null) model.close();
            else weights.close();
        } catch (Exception closeFailure) {
            failure.addSuppressed(closeFailure);
        }
    }

    private static String specificMessage(Throwable failure) {
        Throwable current = failure;
        while (current.getCause() != null && current.getCause() != current) current = current.getCause();
        return current.getMessage() == null || current.getMessage().isBlank()
                ? current.getClass().getSimpleName() : current.getMessage();
    }

    private Backend selectBackend() {
        return switch (backendName()) {
            case "auto" -> MachineSpec.VECTOR_TYPE == MachineSpec.Type.NONE ? jvectorBackend() : simdBackend();
            case "simd" -> simdBackend();
            case "jvector" -> jvectorBackend();
            case "gpu" -> throw new IllegalArgumentException("此 Deliverance 运行时未声明 GPU 能力");
            default -> throw new IllegalArgumentException("未知 tensorBackend");
        };
    }

    private <T> LoadedBackend<T> loadWithBackendFallback(java.util.function.Function<Backend, T> loader) {
        String requested = backendName();
        Backend selected = selectBackend();
        var loaded = BackendFallbackLoader.load(
                "auto".equals(requested) && !"jvector".equals(selected.name()),
                () -> selected, this::jvectorBackend, loader);
        return new LoadedBackend<>(loaded.model(), loaded.backend());
    }

    private String backendName() {
        return String.valueOf(config.loadParameters().getOrDefault("tensorBackend", "auto"))
                .strip().toLowerCase(Locale.ROOT);
    }

    private Backend simdBackend() {
        if (MachineSpec.VECTOR_TYPE == MachineSpec.Type.NONE) {
            throw new IllegalStateException("当前平台没有可用 SIMD Vector Species");
        }
        Backend base = backendBase("simd");
        return base.withProvider(new ConfigurableTensorProvider(new PanamaTensorOperations(
                MachineSpec.VECTOR_TYPE, base.allocator(), base.pool())));
    }

    private Backend jvectorBackend() {
        Backend base = backendBase("jvector");
        return base.withProvider(new ConfigurableTensorProvider(new JVectorTensorOperations()));
    }

    private Backend backendBase(String name) {
        MetricRegistry metrics = new MetricRegistry();
        TensorAllocator allocator = new ArrayQueueTensorAllocator(metrics);
        int configuredThreads = integer(config.loadParameters().get("workerThreads"))
                .orElse(Math.max(1, Runtime.getRuntime().availableProcessors()));
        if (configuredThreads < 1 || configuredThreads > 1024) {
            throw new IllegalArgumentException("workerThreads 超出安全范围");
        }
        WrappedForkJoinPool pool = new WrappedForkJoinPool(new ForkJoinPool(configuredThreads));
        return new Backend(name, metrics, allocator, pool, null);
    }

    private KvBufferCacheSettings kvCache() {
        KvBufferCacheSettings settings = new KvBufferCacheSettings(true);
        integer(config.loadParameters().get("kvCacheMaxEntries")).ifPresent(settings::setMaxEntries);
        return settings;
    }

    private static Protocol.StartupConfig validate(Protocol.StartupConfig value) throws IOException {
        if (value == null || value.runtimeId() == null || value.runtimeId().isBlank()) {
            throw new IllegalArgumentException("运行时 ID 不能为空");
        }
        Path path = Path.of(value.modelPath()).toAbsolutePath().normalize();
        if (Files.isSymbolicLink(path) || !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("模型目录不可用");
        }
        if (value.probeMode()) {
            if (value.contextLength() < 0 || value.embeddingDimensions() < 0) {
                throw new IllegalArgumentException("探测能力参数无效");
            }
        } else if (value.contextLength() < 1) {
            throw new IllegalArgumentException("上下文上限无效");
        }
        return value;
    }

    private static String detectFamily(Path path, ObjectMapper json) throws IOException {
        JsonNode config = json.readTree(path.resolve("config.json").toFile());
        return config.path("model_type").asText("managed");
    }

    private static String finishReason(Response response) {
        if (response.finishReason == null) return "ERROR";
        return switch (response.finishReason) {
            case STOP_TOKEN -> "STOP";
            case MAX_TOKENS -> "LENGTH";
            case TOOL_CALLS, FUNCTION_CALL -> "TOOL_CALLS";
            case CONTENT_FILTER -> "CONTENT_FILTER";
        };
    }

    private static void emitDelta(String requestId, String type, String delta,
                                  Consumer<Protocol.StreamEvent> stream,
                                  CancellationToken cancellation) {
        if (stream == null || delta.isEmpty()) return;
        requireActive(cancellation);
        stream.accept(new Protocol.StreamEvent(requestId, type, delta, List.of(), null,
                null, "", "", System.currentTimeMillis()));
    }

    private static void requireActive(CancellationToken cancellation) {
        if ((cancellation != null && cancellation.isCancellationRequested())
                || Thread.currentThread().isInterrupted()) {
            throw new CancellationException("推理请求已取消");
        }
    }

    private Map<String, Object> readMap(String value) {
        try {
            return json.readValue(value == null || value.isBlank() ? "{}" : value, MAP);
        } catch (Exception failure) {
            throw new IllegalArgumentException("工具调用参数不是有效 JSON", failure);
        }
    }

    private String write(Object value) {
        try { return json.writeValueAsString(value == null ? Map.of() : value); }
        catch (Exception failure) { throw new IllegalStateException("无法序列化工具调用", failure); }
    }

    private static Optional<Number> optionalNumber(Map<String, Object> values, String key) {
        Object value = values.get(key);
        return value == null ? Optional.empty() : Optional.of(number(value, 0));
    }
    private static Number number(Object value, Number fallback) {
        if (value instanceof Number number) return number;
        if (value == null) return fallback;
        try { return Double.parseDouble(String.valueOf(value)); }
        catch (NumberFormatException failure) { throw new IllegalArgumentException("参数必须是数字"); }
    }
    private static Optional<Integer> integer(Object value) {
        return value == null ? Optional.empty() : Optional.of(number(value, 0).intValue());
    }
    private static Optional<Boolean> optionalBoolean(Map<String, Object> values, String key) {
        Object value = values.get(key);
        if (value == null) return Optional.empty();
        if (!(value instanceof Boolean bool)) throw new IllegalArgumentException(key + " 必须是布尔值");
        return Optional.of(bool);
    }
    private static Optional<String> optionalString(Map<String, Object> values, String key) {
        Object value = values.get(key);
        return value == null ? Optional.empty() : Optional.of(String.valueOf(value));
    }
    private static Optional<List<String>> optionalStrings(Map<String, Object> values, String key) {
        Object value = values.get(key);
        if (value == null) return Optional.empty();
        if (value instanceof String string) return Optional.of(List.of(string));
        if (value instanceof List<?> list) return Optional.of(list.stream().map(String::valueOf).toList());
        throw new IllegalArgumentException(key + " 必须是字符串或字符串数组");
    }
    private static String nullable(String value) { return value == null ? "" : value; }
    private static String normalizeUuid(String value) {
        try { return UUID.fromString(value).toString(); }
        catch (Exception ignored) { return UUID.nameUUIDFromBytes(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString(); }
    }

    static final class CountedInferenceFailure extends RuntimeException {
        private final Protocol.Usage usage;
        private final boolean cancelled;

        private CountedInferenceFailure(Throwable cause, Protocol.Usage usage, boolean cancelled) {
            super(cause == null ? "推理失败" : cause.getMessage(), cause);
            this.usage = usage == null ? new Protocol.Usage(0, 0) : usage;
            this.cancelled = cancelled;
        }

        Protocol.Usage usage() { return usage; }
        boolean cancelled() { return cancelled; }
    }

    private static final class UsageTracker {
        private final AtomicLong promptTokens;
        private final AtomicLong completionTokens = new AtomicLong();

        private UsageTracker(long promptTokens) { this.promptTokens = new AtomicLong(promptTokens); }
        private void addPromptTokens(long count) { promptTokens.addAndGet(Math.max(0, count)); }
        private void generatedToken() { completionTokens.incrementAndGet(); }
        private Protocol.Usage snapshot() {
            return new Protocol.Usage(promptTokens.get(), completionTokens.get());
        }
    }

    private record LoadedBackend<T>(T model, Backend backend) { }
    private record LoadedEmbedding(AbstractModel model, float[] finalNorm) { }

    @Override
    public void close() {
        try {
            if (generationModel != null) generationModel.close();
        } catch (Exception failure) {
            log.debug("关闭 Deliverance 生成模型失败", failure);
        }
        try {
            if (embeddingModel != null) embeddingModel.close();
        } catch (Exception failure) {
            log.debug("关闭 Deliverance 嵌入模型失败", failure);
        }
        try {
            backend.close();
        } catch (Exception failure) {
            log.debug("关闭 Deliverance tensor backend 失败", failure);
        }
    }

    private record Backend(String name, MetricRegistry metrics, TensorAllocator allocator,
                           WrappedForkJoinPool pool, ConfigurableTensorProvider provider)
            implements AutoCloseable {
        Backend withProvider(ConfigurableTensorProvider value) {
            return new Backend(name, metrics, allocator, pool, value);
        }
        @Override public void close() { pool.close(); }
    }

    private static final class LocalModelFetcher extends ModelFetcher {
        private final Path path;
        private final String family;

        private LocalModelFetcher(Path path, String family) {
            super("javaclaw", "managed");
            this.path = path;
            this.family = family;
            setDownload(false);
        }

        @Override public File maybeDownload() { return path.toFile(); }
        @Override public Path pathForModel() { return path; }
        @Override public String getName() { return family; }
    }

    /** 对常见 think/channel 标记做增量分类；未知模型仍按普通内容输出。 */
    private static final class ReasoningStreamParser {
        private static final String OPEN = "<think>";
        private static final String CLOSE = "</think>";
        private final Consumer<String> content;
        private final Consumer<String> reasoning;
        private final StringBuilder pending = new StringBuilder();
        private boolean thinking;

        private ReasoningStreamParser(Consumer<String> content, Consumer<String> reasoning) {
            this.content = content;
            this.reasoning = reasoning;
        }

        void accept(String text) {
            pending.append(text);
            drain(false);
        }

        void finish() { drain(true); }

        private void drain(boolean finish) {
            while (!pending.isEmpty()) {
                String marker = thinking ? CLOSE : OPEN;
                int index = pending.indexOf(marker);
                if (index >= 0) {
                    emit(pending.substring(0, index));
                    pending.delete(0, index + marker.length());
                    thinking = !thinking;
                    continue;
                }
                int keep = finish ? 0 : longestMarkerPrefixSuffix(pending, marker);
                int emitLength = pending.length() - keep;
                if (emitLength <= 0) return;
                emit(pending.substring(0, emitLength));
                pending.delete(0, emitLength);
            }
        }

        private void emit(String value) {
            if (!value.isEmpty()) (thinking ? reasoning : content).accept(value);
        }

        private static int longestMarkerPrefixSuffix(StringBuilder text, String marker) {
            int max = Math.min(text.length(), marker.length() - 1);
            for (int length = max; length > 0; length--) {
                if (text.substring(text.length() - length).equals(marker.substring(0, length))) return length;
            }
            return 0;
        }
    }
}
