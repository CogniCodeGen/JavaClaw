package com.javaclaw.application.settings;

import com.javaclaw.application.error.ValidationException;

import java.net.URI;
import java.util.Objects;

/** 模型设置用例：集中校验、持久化和连接探测编排。 */
public final class ModelSettingsUseCase implements ModelSettingsApplicationService {

    private final ModelSettingsPort settings;
    private final ModelSettingsProbePort probes;

    public ModelSettingsUseCase(ModelSettingsPort settings, ModelSettingsProbePort probes) {
        this.settings = Objects.requireNonNull(settings, "settings");
        this.probes = Objects.requireNonNull(probes, "probes");
    }

    @Override
    public Snapshot snapshot() {
        return settings.load();
    }

    @Override
    public SaveResult saveModel(ModelSettings value) {
        ModelSettings validated = validateModel(value);
        settings.saveModel(validated);
        return saved("✓ 已保存，下一轮对话生效", true);
    }

    @Override
    public SaveResult resetModel() {
        settings.resetModel();
        return saved("已恢复为默认配置", true);
    }

    @Override
    public SaveResult saveTiers(TierSettings value) {
        Objects.requireNonNull(value, "settings");
        TierSettings validated = new TierSettings(
                validateTier(value.normal(), "普通模型"),
                validateTier(value.light(), "轻量模型"));
        settings.saveTiers(validated);
        return saved("✓ 已保存，下一轮对话生效", true);
    }

    @Override
    public SaveResult clearTiers() {
        Tier disabled = new Tier(false, "", "", "", "", false);
        settings.saveTiers(new TierSettings(disabled, disabled));
        return saved("已清除分级配置，所有档位回落到高性能模型", true);
    }

    @Override
    public SaveResult saveEmbedding(EmbeddingSettings value) {
        EmbeddingSettings validated = validateEmbedding(value);
        settings.saveEmbedding(validated);
        return saved("✓ 已保存，下一轮对话生效", true);
    }

    @Override
    public ProbeResult probeModel(ModelSettings value) throws Exception {
        return probes.probeModel(validateModel(value));
    }

    @Override
    public ProbeResult probeEmbedding(EmbeddingSettings value) throws Exception {
        return probes.probeEmbedding(validateEmbedding(value), snapshot().embedding());
    }

    private SaveResult saved(String message, boolean refresh) {
        return new SaveResult(snapshot(), message, refresh);
    }

    private static ModelSettings validateModel(ModelSettings value) {
        Objects.requireNonNull(value, "settings");
        required(value.provider(), "模型提供商");
        if (managed(value.provider())) required(value.managedProfileId(), "本地模型档案");
        else {
            httpUri(value.baseUrl(), "API 地址");
            required(value.modelName(), "模型名称");
        }
        range(value.thinkingBudget(), 1024, 65536, "思考预算");
        if (!("HTTP_1_1".equals(value.httpVersion()) || "HTTP_2".equals(value.httpVersion()))) {
            throw new ValidationException("HTTP 版本无效");
        }
        range(value.connectTimeoutSeconds(), 1, 600, "连接超时");
        range(value.readTimeoutSeconds(), 1, 3600, "读取超时");
        range(value.writeTimeoutSeconds(), 1, 600, "写入超时");
        range(value.orchestratorMaxIterations(), 1, 100, "编排最大迭代次数");
        range(value.webAgentMaxIterations(), 1, 50, "Web 最大迭代次数");
        range(value.emailAgentMaxIterations(), 1, 50, "邮件最大迭代次数");
        range(value.maxRepeatedToolCalls(), 1, 50, "最大重复工具调用次数");
        range(value.loopSimilarityThreshold(), 0, 1, "循环相似度阈值");
        range(value.evaluatorPassThreshold(), 1, 5, "评估通过阈值");
        range(value.evaluatorMaxRetries(), 0, 10, "评估最大重试次数");
        return value;
    }

    private static Tier validateTier(Tier value, String label) {
        Objects.requireNonNull(value, label);
        if (!value.enabled()) return new Tier(false, "", "", "", "", false, "");
        required(value.provider(), label + "提供商");
        if (managed(value.provider())) required(value.managedProfileId(), label + "本地模型档案");
        else {
            if (!value.baseUrl().isBlank()) httpUri(value.baseUrl(), label + " API 地址");
            required(value.modelName(), label + "名称");
        }
        return value;
    }

    private static EmbeddingSettings validateEmbedding(EmbeddingSettings value) {
        Objects.requireNonNull(value, "settings");
        required(value.provider(), "嵌入模型提供商");
        if (managed(value.provider())) required(value.managedProfileId(), "本地嵌入档案");
        else {
            httpUri(value.baseUrl(), "嵌入 API 地址");
            required(value.modelName(), "嵌入模型名称");
        }
        range(value.dimensions(), 1, Integer.MAX_VALUE, "向量维度");
        range(value.retrieveLimit(), 1, Integer.MAX_VALUE, "检索返回数量");
        range(value.scoreThreshold(), 0, 1, "检索分数阈值");
        return value;
    }

    private static String required(String value, String label) {
        if (value == null || value.isBlank()) throw new ValidationException(label + "不能为空");
        return value.strip();
    }

    private static boolean managed(String provider) {
        return DefaultModelProviderCatalog.DELIVERANCE.equalsIgnoreCase(provider)
                || "Deliverance（本地托管）".equalsIgnoreCase(provider);
    }

    private static URI httpUri(String value, String label) {
        try {
            URI uri = URI.create(required(value, label));
            if (!("http".equalsIgnoreCase(uri.getScheme())
                    || "https".equalsIgnoreCase(uri.getScheme())) || uri.getHost() == null) {
                throw new IllegalArgumentException("unsupported URI");
            }
            return uri;
        } catch (IllegalArgumentException failure) {
            throw new ValidationException(label + "需为有效的 http:// 或 https:// 地址");
        }
    }

    private static void range(int value, int min, int max, String label) {
        if (value < min || value > max) {
            throw new ValidationException(label + "需在 " + min + " ~ " + max + " 之间");
        }
    }

    private static void range(double value, double min, double max, String label) {
        if (!Double.isFinite(value) || value < min || value > max) {
            throw new ValidationException(label + "需在 " + min + " ~ " + max + " 之间");
        }
    }
}
