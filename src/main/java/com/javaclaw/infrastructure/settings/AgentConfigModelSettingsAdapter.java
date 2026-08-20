package com.javaclaw.infrastructure.settings;

import com.javaclaw.application.settings.ModelSettingsApplicationService.EmbeddingSettings;
import com.javaclaw.application.settings.ModelSettingsApplicationService.ModelSettings;
import com.javaclaw.application.settings.ModelSettingsApplicationService.Snapshot;
import com.javaclaw.application.settings.ModelSettingsApplicationService.Tier;
import com.javaclaw.application.settings.ModelSettingsApplicationService.TierSettings;
import com.javaclaw.application.settings.ModelSettingsPort;
import com.javaclaw.application.inference.InferenceCatalogPort;
import com.javaclaw.application.settings.DefaultModelProviderCatalog;
import com.javaclaw.config.AgentConfig;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Objects;
import java.util.UUID;

/** 将工作区 AgentConfig 适配为不可变模型设置快照。 */
public final class AgentConfigModelSettingsAdapter implements ModelSettingsPort {

    private static final DefaultModelProviderCatalog PROVIDERS = new DefaultModelProviderCatalog();

    private final AgentConfig config;
    private final InferenceCatalogPort inference;
    private final String workspaceId;
    private final TransactionTemplate transactions;

    public AgentConfigModelSettingsAdapter(AgentConfig config) {
        this(config, null, null);
    }

    public AgentConfigModelSettingsAdapter(
            AgentConfig config, InferenceCatalogPort inference, String workspaceId) {
        this(config, inference, workspaceId, null);
    }

    public AgentConfigModelSettingsAdapter(
            AgentConfig config, InferenceCatalogPort inference, String workspaceId,
            PlatformTransactionManager transactionManager) {
        this.config = Objects.requireNonNull(config, "config");
        this.inference = inference;
        this.workspaceId = workspaceId;
        this.transactions = transactionManager == null ? null : new TransactionTemplate(transactionManager);
    }

    @Override
    public synchronized Snapshot load() {
        return new Snapshot(model(), tiers(), embedding(), config.getConfigFilePath());
    }

    @Override
    public synchronized void saveModel(ModelSettings value) {
        atomically(() -> {
            config.setProviderType(value.provider());
            config.setBaseUrl(value.baseUrl());
            config.setModelName(persistedModelName(value.provider(), value.modelName(), value.managedProfileId()));
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
            bind(InferenceCatalogPort.ModelTier.HIGH, value.provider(), value.managedProfileId());
            config.save();
        });
    }

    @Override
    public synchronized void resetModel() {
        atomically(() -> {
            config.resetToDefaults();
            if (inference != null && workspaceId != null) {
                inference.clearBinding(workspaceId, InferenceCatalogPort.ModelTier.HIGH);
            }
        });
    }

    @Override
    public synchronized void saveTiers(TierSettings value) {
        atomically(() -> {
            writeTier(value.normal(), false);
            writeTier(value.light(), true);
            config.save();
        });
    }

    @Override
    public synchronized void saveEmbedding(EmbeddingSettings value) {
        atomically(() -> {
            config.setRagEnabled(value.enabled());
            config.setRagEmbeddingProvider(value.provider());
            config.setRagEmbeddingBaseUrl(value.baseUrl());
            config.setRagEmbeddingApiKey(value.apiKey());
            config.setRagEmbeddingModelName(persistedModelName(
                    value.provider(), value.modelName(), value.managedProfileId()));
            config.setRagEmbeddingDimensions(value.dimensions());
            config.setRagRetrieveLimit(value.retrieveLimit());
            config.setRagScoreThreshold(value.scoreThreshold());
            bind(InferenceCatalogPort.ModelTier.EMBEDDING, value.provider(), value.managedProfileId());
            config.save();
        });
    }

    private ModelSettings model() {
        return new ModelSettings(
                provider(config.getProviderType()), config.getBaseUrl(), config.getModelName(),
                config.getApiKey(), config.isThinkingEnabled(), config.getThinkingBudget(),
                config.getHttpVersion(), config.getConnectTimeoutSeconds(),
                config.getReadTimeoutSeconds(), config.getWriteTimeoutSeconds(),
                config.getOrchestratorMaxIters(), config.getWebAgentMaxIters(),
                config.getEmailAgentMaxIters(), config.getMaxRepeatedToolCalls(),
                config.getLoopSimilarityThreshold(), config.getEvaluatorPassThreshold(),
                config.getEvaluatorMaxRetries(), binding(InferenceCatalogPort.ModelTier.HIGH));
    }

    private TierSettings tiers() {
        return new TierSettings(
                new Tier(config.isNormalTierConfigured(), provider(config.getNormalProviderType()),
                        config.getNormalBaseUrl(),
                        config.isNormalTierConfigured() ? config.getNormalModelName() : "",
                        config.isNormalTierConfigured() ? config.getNormalApiKey() : "",
                        config.isNormalThinkingEnabled(), bindingOrHigh(InferenceCatalogPort.ModelTier.NORMAL)),
                new Tier(config.isLightTierConfigured(), provider(config.getLightProviderType()),
                        config.getLightBaseUrl(),
                        config.isLightTierConfigured() ? config.getLightModelName() : "",
                        config.isLightTierConfigured() ? config.getLightApiKey() : "",
                        config.isLightThinkingEnabled(), bindingOrHigh(InferenceCatalogPort.ModelTier.LIGHT)));
    }

    private EmbeddingSettings embedding() {
        return new EmbeddingSettings(
                config.isRagEnabled(), provider(config.getRagEmbeddingProvider()),
                config.getRagEmbeddingBaseUrl(), config.getRagEmbeddingApiKey(),
                config.getRagEmbeddingModelName(), config.getRagEmbeddingDimensions(),
                config.getRagRetrieveLimit(), config.getRagScoreThreshold(),
                binding(InferenceCatalogPort.ModelTier.EMBEDDING));
    }

    private void writeTier(Tier value, boolean light) {
        String provider = value.enabled() ? value.provider() : "";
        String baseUrl = value.enabled() ? value.baseUrl() : "";
        String model = value.enabled()
                ? persistedModelName(value.provider(), value.modelName(), value.managedProfileId()) : "";
        String apiKey = value.enabled() ? value.apiKey() : "";
        if (light) {
            config.setLightProviderType(provider);
            config.setLightBaseUrl(baseUrl);
            config.setLightModelName(model);
            config.setLightApiKey(apiKey);
            config.setLightThinkingEnabled(value.thinkingEnabled());
            bind(InferenceCatalogPort.ModelTier.LIGHT, provider, value.managedProfileId());
        } else {
            config.setNormalProviderType(provider);
            config.setNormalBaseUrl(baseUrl);
            config.setNormalModelName(model);
            config.setNormalApiKey(apiKey);
            config.setNormalThinkingEnabled(value.thinkingEnabled());
            bind(InferenceCatalogPort.ModelTier.NORMAL, provider, value.managedProfileId());
        }
    }

    private String binding(InferenceCatalogPort.ModelTier tier) {
        if (inference == null || workspaceId == null) return "";
        UUID value = inference.bindings(workspaceId).get(tier);
        return value == null ? "" : value.toString();
    }

    private String bindingOrHigh(InferenceCatalogPort.ModelTier tier) {
        String value = binding(tier);
        return value.isBlank() ? binding(InferenceCatalogPort.ModelTier.HIGH) : value;
    }

    private void bind(InferenceCatalogPort.ModelTier tier, String provider, String profileId) {
        if (inference == null || workspaceId == null) return;
        if (!DefaultModelProviderCatalog.DELIVERANCE.equals(PROVIDERS.normalizeId(provider))
                || profileId == null || profileId.isBlank()) {
            inference.clearBinding(workspaceId, tier);
            return;
        }
        UUID id = UUID.fromString(profileId);
        var profile = inference.profile(id)
                .orElseThrow(() -> new IllegalArgumentException("本地模型档案不存在"));
        boolean embeddingTier = tier == InferenceCatalogPort.ModelTier.EMBEDDING;
        boolean embeddingProfile = profile.kind()
                == com.javaclaw.inference.api.InferenceModelProfile.Kind.EMBEDDING;
        if (profile.state() != com.javaclaw.inference.api.InferenceModelProfile.State.READY) {
            throw new IllegalStateException("本地模型档案尚未通过加载探测");
        }
        if (embeddingTier != embeddingProfile) {
            throw new IllegalArgumentException("本地模型档案类型与模型档位不匹配");
        }
        inference.bind(workspaceId, tier, id);
    }

    private static String persistedModelName(String provider, String model, String profileId) {
        return DefaultModelProviderCatalog.DELIVERANCE.equals(PROVIDERS.normalizeId(provider))
                && profileId != null && !profileId.isBlank() ? profileId : model;
    }

    private static String provider(String value) { return PROVIDERS.normalizeId(value); }

    private void atomically(Runnable work) {
        if (transactions == null) {
            work.run();
            return;
        }
        try {
            transactions.executeWithoutResult(ignored -> work.run());
        } catch (RuntimeException failure) {
            // H2 has rolled back, so reset AgentConfig's mutable property mirror to the same state.
            config.reload();
            throw failure;
        }
    }
}
