package com.javaclaw.infrastructure.onboarding;

import com.javaclaw.application.onboarding.OnboardingApplicationService.ProviderSetup;
import com.javaclaw.application.onboarding.OnboardingSettingsPort;
import com.javaclaw.config.AgentConfig;

import java.util.Objects;
import java.util.function.Supplier;

/** 把遗留 AgentConfig 隔离在首次向导的基础设施适配器内。 */
public final class AgentConfigOnboardingSettings implements OnboardingSettingsPort {

    private final Supplier<AgentConfig> configs;

    public AgentConfigOnboardingSettings(Supplier<AgentConfig> configs) {
        this.configs = Objects.requireNonNull(configs, "configs");
    }

    @Override
    public boolean completed() {
        return configs.get().isFirstUseGuidanceDone();
    }

    @Override
    public void save(ProviderSetup setup, String apiKey) {
        Objects.requireNonNull(setup, "setup");
        AgentConfig config = configs.get();
        synchronized (config) {
            config.setProviderType(setup.provider().id());
            config.setBaseUrl(setup.baseUrl());
            config.setModelName(setup.modelName());
            config.setApiKey(apiKey);
            config.save();
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
