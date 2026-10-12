package com.javaclaw.application.onboarding;

import com.javaclaw.application.onboarding.OnboardingApplicationService.ProviderSetup;

/** 当前工作区首次向导设置端口；实现必须保证单次保存不会发布半成品配置。 */
public interface OnboardingSettingsPort {

    boolean completed();

    void save(ProviderSetup setup, String apiKey);

    void markCompleted();
}
