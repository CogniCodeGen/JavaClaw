package com.javaclaw.infrastructure.onboarding;

import com.javaclaw.application.onboarding.OnboardingApplicationService.ProviderSetup;
import com.javaclaw.application.onboarding.OnboardingSettingsPort;
import com.javaclaw.config.AgentConfig;
import com.javaclaw.application.inference.InferenceCatalogPort;
import com.javaclaw.application.settings.DefaultModelProviderCatalog;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Objects;
import java.util.function.Supplier;

/** 把遗留 AgentConfig 隔离在首次向导的基础设施适配器内。 */
public final class AgentConfigOnboardingSettings implements OnboardingSettingsPort {

    private static final DefaultModelProviderCatalog PROVIDERS = new DefaultModelProviderCatalog();

    private final Supplier<AgentConfig> configs;
    private final InferenceCatalogPort inference;
    private final Supplier<String> workspaceIds;
    private final TransactionTemplate transactions;

    public AgentConfigOnboardingSettings(Supplier<AgentConfig> configs) {
        this(configs, null, () -> "", null);
    }

    public AgentConfigOnboardingSettings(Supplier<AgentConfig> configs,
                                         InferenceCatalogPort inference,
                                         Supplier<String> workspaceIds,
                                         org.springframework.transaction.PlatformTransactionManager transactions) {
        this.configs = Objects.requireNonNull(configs, "configs");
        this.inference = inference;
        this.workspaceIds = Objects.requireNonNull(workspaceIds, "workspaceIds");
        this.transactions = transactions == null ? null : new TransactionTemplate(transactions);
    }

    @Override
    public boolean completed() {
        return configs.get().isFirstUseGuidanceDone();
    }

    @Override
    public void save(ProviderSetup setup, String apiKey) {
        Objects.requireNonNull(setup, "setup");
        if (transactions == null) {
            saveAtomically(setup, apiKey);
            return;
        }
        try {
            transactions.executeWithoutResult(ignored -> saveAtomically(setup, apiKey));
        } catch (RuntimeException failure) {
            // Transaction rollback restores H2; restore the mutable in-memory mirror as well.
            AgentConfig config = configs.get();
            synchronized (config) { config.reload(); }
            throw failure;
        }
    }

    private void saveAtomically(ProviderSetup setup, String apiKey) {
        AgentConfig config = configs.get();
        synchronized (config) {
            config.setProviderType(PROVIDERS.normalizeId(setup.provider().id()));
            config.setBaseUrl(setup.baseUrl());
            config.setModelName(setup.modelName());
            config.setApiKey(apiKey);
            config.save();
            if (inference != null) {
                String workspaceId = workspaceIds.get();
                if (setup.provider().managed()) {
                    java.util.UUID profileId = java.util.UUID.fromString(setup.managedProfileId());
                    var profile = inference.profile(profileId).orElseThrow(
                            () -> new IllegalStateException("本地模型档案不存在"));
                    if (profile.state() != com.javaclaw.inference.api.InferenceModelProfile.State.READY) {
                        throw new IllegalStateException("本地模型档案尚未通过探测");
                    }
                    if (profile.kind()
                            != com.javaclaw.inference.api.InferenceModelProfile.Kind.GENERATION) {
                        throw new IllegalArgumentException("首启主模型必须选择生成模型档案");
                    }
                    inference.bind(workspaceId, InferenceCatalogPort.ModelTier.HIGH, profileId);
                } else {
                    inference.clearBinding(workspaceId, InferenceCatalogPort.ModelTier.HIGH);
                }
            }
        }
    }

    @Override
    public void markCompleted() {
        AgentConfig config = configs.get();
        synchronized (config) {
            if (config.isFirstUseGuidanceDone()) return;
            config.setFirstUseGuidanceDone(true);
            config.save();
        }
    }
}
