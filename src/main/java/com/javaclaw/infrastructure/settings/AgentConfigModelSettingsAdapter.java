package com.javaclaw.infrastructure.settings;

import com.javaclaw.application.settings.ModelSettingsApplicationService.EmbeddingSettings;
import com.javaclaw.application.settings.ModelSettingsApplicationService.ModelSettings;
import com.javaclaw.application.settings.ModelSettingsApplicationService.Snapshot;
import com.javaclaw.application.settings.ModelSettingsApplicationService.Tier;
import com.javaclaw.application.settings.ModelSettingsApplicationService.TierSettings;
import com.javaclaw.application.settings.ModelSettingsPort;
import com.javaclaw.config.AgentConfig;

import java.util.Objects;

/** 将工作区 AgentConfig 适配为不可变模型设置快照。 */
public final class AgentConfigModelSettingsAdapter implements ModelSettingsPort {

    private final AgentConfig config;

    public AgentConfigModelSettingsAdapter(AgentConfig config) {
        this.config = Objects.requireNonNull(config, "config");
    }

    @Override
    public synchronized Snapshot load() {
        return new Snapshot(model(), tiers(), embedding(), config.getConfigFilePath());
    }

    @Override
    public synchronized void saveModel(ModelSettings value) {
        config.setProviderType(value.provider());
        config.setBaseUrl(value.baseUrl());
        config.setModelName(value.modelName());
        config.setApiKey(value.apiKey());
        config.setThinkingEnabled(value.thinkingEnabled());
        config.setThinkingBudget(value.thinkingBudget());
        config.setHttpVersion(value.httpVersion());
        config.setConnectTimeoutSeconds(value.connectTimeoutSeconds());
        config.setReadTimeoutSeconds(value.readTimeoutSeconds());
        config.setWriteTimeoutSeconds(value.writeTimeoutSeconds());
        config.setOrchestratorMaxIters(value.orchestratorMaxIterations());
        config.setWebAgentMaxIters(value.webAgentMaxIterations());
        config.setEmailAgentMaxIters(value.emailAgentMaxIterations());
        config.setMaxRepeatedToolCalls(value.maxRepeatedToolCalls());
        config.setLoopSimilarityThreshold(value.loopSimilarityThreshold());
        config.setEvaluatorPassThreshold(value.evaluatorPassThreshold());
        config.setEvaluatorMaxRetries(value.evaluatorMaxRetries());
        config.save();
    }

    @Override
    public synchronized void resetModel() {
        config.resetToDefaults();
    }

    @Override
    public synchronized void saveTiers(TierSettings value) {
        writeTier(value.normal(), false);
        writeTier(value.light(), true);
        config.save();
    }

    @Override
    public synchronized void saveEmbedding(EmbeddingSettings value) {
        config.setRagEnabled(value.enabled());
        config.setRagEmbeddingProvider(value.provider());
        config.setRagEmbeddingBaseUrl(value.baseUrl());
        config.setRagEmbeddingApiKey(value.apiKey());
        config.setRagEmbeddingModelName(value.modelName());
        config.setRagEmbeddingDimensions(value.dimensions());
        config.setRagRetrieveLimit(value.retrieveLimit());
        config.setRagScoreThreshold(value.scoreThreshold());
        config.save();
    }

    private ModelSettings model() {
        return new ModelSettings(
                config.getProviderType(), config.getBaseUrl(), config.getModelName(),
                config.getApiKey(), config.isThinkingEnabled(), config.getThinkingBudget(),
                config.getHttpVersion(), config.getConnectTimeoutSeconds(),
                config.getReadTimeoutSeconds(), config.getWriteTimeoutSeconds(),
                config.getOrchestratorMaxIters(), config.getWebAgentMaxIters(),
                config.getEmailAgentMaxIters(), config.getMaxRepeatedToolCalls(),
                config.getLoopSimilarityThreshold(), config.getEvaluatorPassThreshold(),
                config.getEvaluatorMaxRetries());
    }

    private TierSettings tiers() {
        return new TierSettings(
                new Tier(config.isNormalTierConfigured(), config.getNormalProviderType(),
                        config.getNormalBaseUrl(),
                        config.isNormalTierConfigured() ? config.getNormalModelName() : "",
                        config.isNormalTierConfigured() ? config.getNormalApiKey() : "",
                        config.isNormalThinkingEnabled()),
                new Tier(config.isLightTierConfigured(), config.getLightProviderType(),
                        config.getLightBaseUrl(),
                        config.isLightTierConfigured() ? config.getLightModelName() : "",
                        config.isLightTierConfigured() ? config.getLightApiKey() : "",
                        config.isLightThinkingEnabled()));
    }

    private EmbeddingSettings embedding() {
        return new EmbeddingSettings(
                config.isRagEnabled(), config.getRagEmbeddingProvider(),
                config.getRagEmbeddingBaseUrl(), config.getRagEmbeddingApiKey(),
                config.getRagEmbeddingModelName(), config.getRagEmbeddingDimensions(),
                config.getRagRetrieveLimit(), config.getRagScoreThreshold());
    }

    private void writeTier(Tier value, boolean light) {
        String provider = value.enabled() ? value.provider() : "";
        String baseUrl = value.enabled() ? value.baseUrl() : "";
        String model = value.enabled() ? value.modelName() : "";
        String apiKey = value.enabled() ? value.apiKey() : "";
        if (light) {
            config.setLightProviderType(provider);
            config.setLightBaseUrl(baseUrl);
            config.setLightModelName(model);
            config.setLightApiKey(apiKey);
            config.setLightThinkingEnabled(value.thinkingEnabled());
        } else {
            config.setNormalProviderType(provider);
            config.setNormalBaseUrl(baseUrl);
            config.setNormalModelName(model);
            config.setNormalApiKey(apiKey);
            config.setNormalThinkingEnabled(value.thinkingEnabled());
        }
    }
}
