package com.javaclaw.agent.model;

import com.javaclaw.config.AgentConfig;
import io.agentscope.core.embedding.EmbeddingModel;
import io.agentscope.core.embedding.openai.OpenAITextEmbedding;
import io.agentscope.core.formatter.anthropic.AnthropicMultiAgentFormatter;
import io.agentscope.core.formatter.dashscope.DashScopeMultiAgentFormatter;
import io.agentscope.core.formatter.gemini.GeminiMultiAgentFormatter;
import io.agentscope.core.formatter.ollama.OllamaMultiAgentFormatter;
import io.agentscope.core.model.*;
import io.agentscope.core.model.transport.HttpTransport;
import io.agentscope.core.model.transport.HttpTransportConfig;
import io.agentscope.core.model.transport.JdkHttpTransport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.http.HttpClient;
import java.time.Duration;

/**
 * 模型工厂 — 负责创建 HTTP 传输层和 ChatModel 实例
 *
 * <p>集中管理与各模型提供商 API 的连接配置，
 * 所有子智能体和编排智能体共享同一个 HTTP 传输层。</p>
 *
 * <p>支持以下模型提供商：</p>
 * <ul>
 *     <li><b>OpenAI</b> — OpenAI 兼容 API（GPT、DeepSeek、GLM、本地服务等）</li>
 *     <li><b>DashScope</b> — 阿里云 DashScope（通义千问系列）</li>
 *     <li><b>Anthropic</b> — Anthropic Claude 系列</li>
 *     <li><b>Gemini</b> — Google Gemini 系列</li>
 *     <li><b>Ollama</b> — 本地 Ollama 实例</li>
 * </ul>
 *
 * <p>分级模型（{@link ModelTier}）：HIGH / NORMAL / LIGHT 三档对应不同的 provider + modelName + apiKey 配置。
 * NORMAL / LIGHT 未配置时整组回落到 HIGH，保持向后兼容。</p>
 *
 * @author JavaClaw
 */
public class ModelFactory {

    private static final Logger log = LoggerFactory.getLogger(ModelFactory.class);

    /** 各 tier 的连接快照（构造时一次性读出，避免运行期反复读 Properties） */
    private record TierSpec(
            String providerType,
            String baseUrl,
            String modelName,
            String apiKey,
            boolean thinkingEnabled,
            int thinkingBudget) { }

    /** 共享的 HTTP 传输层（OpenAI / DashScope / Ollama 使用），经 {@link UsageMeteredTransport} 包装以截获缓存命中用量 */
    private final HttpTransport httpTransport;

    /** 底层 Jdk 传输（保留引用用于关闭） */
    private final JdkHttpTransport rawTransport;

    private final TierSpec highSpec;
    private final TierSpec normalSpec;
    private final TierSpec lightSpec;

    /** 单次模型请求总超时（秒）— 覆盖 AgentScope MODEL_DEFAULTS 的 5 分钟默认 */
    private final long modelRequestTimeoutSeconds;

    public ModelFactory() {
        AgentConfig config = AgentConfig.getInstance();

        // 构建 HTTP 传输层（使用 HttpTransportConfig 统一管理超时）
        HttpTransportConfig transportConfig = HttpTransportConfig.builder()
                .connectTimeout(Duration.ofSeconds(config.getConnectTimeoutSeconds()))
                .readTimeout(Duration.ofSeconds(config.getReadTimeoutSeconds()))
                .writeTimeout(Duration.ofSeconds(config.getWriteTimeoutSeconds()))
                .build();

        // 根据配置选择 HTTP 版本（默认 HTTP/1.1）
        HttpClient.Version httpVersion = config.isHttp2()
                ? HttpClient.Version.HTTP_2
                : HttpClient.Version.HTTP_1_1;

        HttpClient httpClient = HttpClient.newBuilder()
                .version(httpVersion)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(config.getConnectTimeoutSeconds()))
                .build();

        this.rawTransport = JdkHttpTransport.builder()
                .config(transportConfig)
                .client(httpClient)
                .build();
        // 包一层用量观测：从原始响应截获 prompt_tokens_details.cached_tokens（AgentScope ChatUsage 不透传）
        this.httpTransport = new UsageMeteredTransport(rawTransport);
        log.info("共享 HTTP 传输层已创建 — httpVersion: {}, connectTimeout: {}s, readTimeout: {}s",
                httpVersion, config.getConnectTimeoutSeconds(), config.getReadTimeoutSeconds());

        this.modelRequestTimeoutSeconds = config.getModelRequestTimeoutSeconds();

        int thinkingBudget = config.getThinkingBudget();
        this.highSpec = new TierSpec(
                config.getProviderType(),
                config.getBaseUrl(),
                config.getModelName(),
                config.getApiKey(),
                config.isThinkingEnabled(),
                thinkingBudget);
        this.normalSpec = new TierSpec(
                config.getNormalProviderType(),
                config.getNormalBaseUrl(),
                config.getNormalModelName(),
                config.getNormalApiKey(),
                config.isNormalThinkingEnabled(),
                thinkingBudget);
        this.lightSpec = new TierSpec(
                config.getLightProviderType(),
                config.getLightBaseUrl(),
                config.getLightModelName(),
                config.getLightApiKey(),
                // 轻量始终强制关闭思考，避免路由/分类阻塞数十秒
                false,
                thinkingBudget);

        log.info("模型分级已加载 — HIGH: {} ({}), NORMAL: {} ({}, 独立配置={}), LIGHT: {} ({}, 独立配置={})",
                highSpec.modelName(), highSpec.providerType(),
                normalSpec.modelName(), normalSpec.providerType(), config.isNormalTierConfigured(),
                lightSpec.modelName(), lightSpec.providerType(), config.isLightTierConfigured());
    }

    // ==================== 公共入口 ====================

    /**
     * 创建普通（NORMAL）档单智能体聊天模型实例。
     * <p>用于子专家、单步执行体、知识专家、记忆压缩等常规任务。</p>
     */
    public ChatModelBase createChatModel() {
        return createModel(normalSpec, false);
    }

    /**
     * 创建普通（NORMAL）档带 MultiAgentFormatter 的聊天模型实例。
     * <p>用于 MsgHub 多智能体协作场景中的普通参与者。</p>
     */
    public ChatModelBase createMultiAgentChatModel() {
        return createModel(normalSpec, true);
    }

    /**
     * 创建高性能（HIGH）档聊天模型实例。
     * <p>用于主编排器、规划智能体、ChallengerAgent、PlanEvolver 等复杂推理与规划任务。</p>
     */
    public ChatModelBase createHighChatModel() {
        return createModel(highSpec, false);
    }

    /**
     * 创建高性能（HIGH）档带 MultiAgentFormatter 的聊天模型实例。
     * <p>用于规划模式 MsgHub 协调者。</p>
     */
    public ChatModelBase createHighMultiAgentChatModel() {
        return createModel(highSpec, true);
    }

    /**
     * 创建轻量（LIGHT）档聊天模型实例（强制关闭 thinking）。
     *
     * <p>路由/分类/视觉描述/记忆蒸馏等一次性任务。开 thinking 会导致每次对话白白阻塞数十秒到数分钟，
     * 严重影响 UX（尤其多模态消息时表现为"发送后无响应"）。</p>
     */
    public ChatModelBase createLightChatModel() {
        return createModel(lightSpec, false);
    }

    /**
     * 按显式 tier 创建聊天模型（高级调用方使用）。
     */
    public ChatModelBase createChatModel(ModelTier tier, boolean multiAgent) {
        TierSpec spec = switch (tier) {
            case HIGH -> highSpec;
            case NORMAL -> normalSpec;
            case LIGHT -> lightSpec;
        };
        return createModel(spec, multiAgent);
    }

    /**
     * 创建用于结构化输出（{@code agent.call(msgs, Pojo.class)}）的聊天模型实例 —— 强制关闭 thinking。
     *
     * <p>结构化输出会把请求的 {@code tool_choice} 置为 {@code required} 或具体工具对象，而 DashScope /
     * 通义千问等在思考模式下禁止该取值，直接返回
     * {@code 400 InvalidParameter: The tool_choice parameter does not support being set to required or
     * object in thinking mode}。因此凡走结构化输出的调用一律经本方法取模型，从源头规避该冲突，
     * 与具体档位的 thinking 配置无关。</p>
     *
     * @param tier 期望档位；其 thinking 开关会被强制覆盖为关闭
     */
    public ChatModelBase createStructuredChatModel(ModelTier tier) {
        TierSpec spec = switch (tier) {
            case HIGH -> highSpec;
            case NORMAL -> normalSpec;
            case LIGHT -> lightSpec;
        };
        return createModel(withThinkingDisabled(spec), false);
    }

    /** 返回强制关闭 thinking 的 spec 副本；本就关闭则原样返回。 */
    private static TierSpec withThinkingDisabled(TierSpec spec) {
        return spec.thinkingEnabled()
                ? new TierSpec(spec.providerType(), spec.baseUrl(), spec.modelName(),
                        spec.apiKey(), false, spec.thinkingBudget())
                : spec;
    }

    /**
     * 是否为 DashScope 的 OpenAI 兼容端点（providerType=OpenAI 且 baseUrl 指向 dashscope/aliyuncs）。
     * <p>此类端点的 Qwen3 思考模型默认开启思考，需显式 {@code enable_thinking=false} 才能关闭。</p>
     */
    private static boolean isDashScopeOpenAiCompat(TierSpec spec) {
        if (!"OpenAI".equals(spec.providerType())) return false;
        String url = spec.baseUrl();
        if (url == null) return false;
        String u = url.toLowerCase();
        return u.contains("dashscope") || u.contains("aliyuncs");
    }

    // ==================== 嵌入模型 ====================

    /**
     * 创建文本嵌入模型（OpenAI 兼容），统一复用 {@code rag.embedding.*} 配置。
     *
     * <p>供知识库 RAG 与长期记忆向量索引共用。构造本身不发起网络请求；
     * 端点/Key 未配置时，失败发生在实际 embed 调用，由调用方降级处理。</p>
     */
    public EmbeddingModel createEmbeddingModel() {
        AgentConfig config = AgentConfig.getInstance();
        String modelName = config.getRagEmbeddingModelName();
        int dimensions = config.getRagEmbeddingDimensions();
        String apiKey = config.getRagEmbeddingApiKey();
        String baseUrl = config.getRagEmbeddingBaseUrl();

        OpenAITextEmbedding.Builder builder = OpenAITextEmbedding.builder()
                .modelName(modelName)
                .dimensions(dimensions);
        // 始终设置 apiKey：本地 OpenAI 兼容端点（如 LM Studio / Ollama）无需密钥，但嵌入客户端
        // build() 在 apiKey 为空时会抛异常，导致整个嵌入模型创建失败（表现为"嵌入模型未创建"）。
        // 故空密钥时填占位符，本地端点会忽略它，远端端点用户本就需填真实密钥。
        builder.apiKey(apiKey != null && !apiKey.isBlank() ? apiKey : "not-needed");
        if (baseUrl != null && !baseUrl.isBlank()) {
            builder.baseUrl(baseUrl);
        }
        log.info("嵌入模型已创建（OpenAI 兼容）— model: {}, dimensions: {}, baseUrl: {}",
                modelName, dimensions, baseUrl);
        return builder.build();
    }

    // ==================== 内部分发 ====================

    /**
     * 统一的模型创建方法，按 tier 配置 + multiAgent 决定 provider 与 formatter
     */
    private ChatModelBase createModel(TierSpec spec, boolean multiAgent) {
        GenerateOptions options = buildGenerateOptions(spec);
        return switch (spec.providerType()) {
            case "DashScope" -> createDashScopeModel(spec, options, multiAgent);
            case "Anthropic" -> createAnthropicModel(spec, options, multiAgent);
            case "Gemini" -> createGeminiModel(spec, options, multiAgent);
            case "Ollama" -> createOllamaModel(spec, multiAgent);
            default -> createOpenAIModel(spec, options, multiAgent);
        };
    }

    /**
     * 构建 tier 专属生成参数。
     *
     * <p>cacheControl(true)：让 formatter 自动给 system message 和最后一条 message 打上
     * cache_control:ephemeral 标记，Anthropic / DashScope / OpenAI-兼容 API 会命中
     * prompt cache 显著降低输入 token 费用（命中可省 90%）；Gemini / Ollama 会忽略标记，
     * 无副作用。</p>
     */
    private GenerateOptions buildGenerateOptions(TierSpec spec) {
        GenerateOptions.Builder b = GenerateOptions.builder().cacheControl(true);
        if (spec.thinkingEnabled()) {
            b.thinkingBudget(spec.thinkingBudget());
        } else if (isDashScopeOpenAiCompat(spec)) {
            // 通义千问（Qwen3）思考型模型在 DashScope 的 OpenAI 兼容端默认开启思考模式，
            // 仅"省略"思考参数并不能关闭它；而思考模式下禁止 tool_choice=required/具体工具对象，
            // 会令结构化输出（agent.call(msgs, Pojo.class) 强制 tool_choice）报 400。
            // 故对这类端点的非思考调用显式下发 enable_thinking=false，从源头关闭思考。
            b.additionalBodyParam("enable_thinking", false);
        }
        // 覆盖 AgentScope MODEL_DEFAULTS 的 5 分钟单次请求超时（PT5M）：长任务（如 SINGLE 通道
        // 一次性生成整个项目）单轮生成可能超过 5 分钟而被中断报 ModelException。用配置值覆盖超时，
        // 同时通过 mergeConfigs 保留 MODEL_DEFAULTS 的重试策略（maxAttempts / backoff）。
        ExecutionConfig exec = ExecutionConfig.mergeConfigs(
                ExecutionConfig.builder()
                        .timeout(Duration.ofSeconds(modelRequestTimeoutSeconds))
                        .build(),
                ExecutionConfig.MODEL_DEFAULTS);
        b.executionConfig(exec);
        return b.build();
    }

    // ==================== 各提供商模型创建（统一处理单/多智能体） ====================

    private OpenAIChatModel createOpenAIModel(TierSpec spec, GenerateOptions options, boolean multiAgent) {
        return OpenAIChatModel.builder()
                .apiKey(spec.apiKey())
                .modelName(spec.modelName())
                .baseUrl(spec.baseUrl())
                .httpTransport(httpTransport)
                .formatter(multiAgent ? new ToolSchemaFixMultiAgentFormatter() : new ToolSchemaFixFormatter())
                .stream(true)
                .generateOptions(options)
                .build();
    }

    private DashScopeChatModel createDashScopeModel(TierSpec spec, GenerateOptions options, boolean multiAgent) {
        DashScopeChatModel.Builder builder = DashScopeChatModel.builder()
                .apiKey(spec.apiKey())
                .modelName(spec.modelName())
                .stream(true)
                .httpTransport(httpTransport)
                .defaultOptions(options)
                .enableThinking(spec.thinkingEnabled());

        if (multiAgent) {
            builder.formatter(new DashScopeMultiAgentFormatter());
        }

        String baseUrl = spec.baseUrl();
        if (baseUrl != null && !baseUrl.isBlank()) {
            builder.baseUrl(baseUrl);
        }

        return builder.build();
    }

    private AnthropicChatModel createAnthropicModel(TierSpec spec, GenerateOptions options, boolean multiAgent) {
        AnthropicChatModel.Builder builder = AnthropicChatModel.builder()
                .apiKey(spec.apiKey())
                .modelName(spec.modelName())
                .stream(true)
                .defaultOptions(options);

        if (multiAgent) {
            builder.formatter(new AnthropicMultiAgentFormatter());
        }

        String baseUrl = spec.baseUrl();
        if (baseUrl != null && !baseUrl.isBlank()) {
            builder.baseUrl(baseUrl);
        }

        return builder.build();
    }

    private GeminiChatModel createGeminiModel(TierSpec spec, GenerateOptions options, boolean multiAgent) {
        GeminiChatModel.Builder builder = GeminiChatModel.builder()
                .apiKey(spec.apiKey())
                .modelName(spec.modelName())
                .streamEnabled(true)
                .defaultOptions(options);

        if (multiAgent) {
            builder.formatter(new GeminiMultiAgentFormatter());
        }

        return builder.build();
    }

    private OllamaChatModel createOllamaModel(TierSpec spec, boolean multiAgent) {
        OllamaChatModel.Builder builder = OllamaChatModel.builder()
                .modelName(spec.modelName())
                .httpTransport(httpTransport);

        if (multiAgent) {
            builder.formatter(new OllamaMultiAgentFormatter());
        }

        String baseUrl = spec.baseUrl();
        if (baseUrl != null && !baseUrl.isBlank()) {
            builder.baseUrl(baseUrl);
        }

        return builder.build();
    }
}
