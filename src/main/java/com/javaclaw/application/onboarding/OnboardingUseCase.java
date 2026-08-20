package com.javaclaw.application.onboarding;

import com.javaclaw.application.error.RejectedException;
import com.javaclaw.application.error.ValidationException;
import com.javaclaw.application.onboarding.OnboardingApplicationService.ProbeCommand;
import com.javaclaw.application.onboarding.OnboardingApplicationService.ProbeResult;
import com.javaclaw.application.onboarding.OnboardingApplicationService.Provider;
import com.javaclaw.application.onboarding.OnboardingApplicationService.ProviderSetup;
import com.javaclaw.application.onboarding.OnboardingApplicationService.ProviderSetupCommand;

import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import com.javaclaw.application.settings.DefaultModelProviderCatalog;
import com.javaclaw.application.settings.ModelProviderCatalog;

/** 首次向导校验、保存与连接探测用例。 */
public final class OnboardingUseCase implements OnboardingApplicationService {

    private final OnboardingSettingsPort settings;
    private final ConnectionProbePort connection;
    private final ModelProviderCatalog catalog;
    private final List<Provider> providers;

    public OnboardingUseCase(
            OnboardingSettingsPort settings,
            ConnectionProbePort connection) {
        this(settings, connection, new DefaultModelProviderCatalog());
    }

    public OnboardingUseCase(OnboardingSettingsPort settings, ConnectionProbePort connection,
                             ModelProviderCatalog catalog) {
        this.settings = Objects.requireNonNull(settings, "settings");
        this.connection = Objects.requireNonNull(connection, "connection");
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.providers = this.catalog.providers().stream()
                .filter(provider -> provider.capabilities().contains(ModelProviderCatalog.Capability.CHAT))
                .map(this::onboardingProvider).toList();
    }

    @Override
    public boolean required() {
        return !settings.completed();
    }

    @Override
    public List<Provider> providers() {
        return providers;
    }

    @Override
    public ProviderSetup save(ProviderSetupCommand command) {
        Objects.requireNonNull(command, "command");
        Provider provider = requireProvider(command.providerId());
        String managedProfile = command.managedProfileId() == null ? "" : command.managedProfileId().strip();
        String baseUrl;
        String model;
        String apiKey;
        if (provider.managed()) {
            managedProfile = required(managedProfile, "请先准备并选择通过验证的本地模型档案");
            baseUrl = "";
            model = managedProfile;
            apiKey = "not-needed";
        } else {
            baseUrl = required(command.baseUrl(), "Base URL 不能为空");
            validateHttpUri(baseUrl);
            model = required(command.modelName(), "模型名称不能为空");
            apiKey = provider.local() ? "not-needed"
                    : required(command.apiKey(), "云端模型需要填写 API Key");
        }
        ProviderSetup setup = new ProviderSetup(provider, baseUrl, model, managedProfile);
        settings.save(setup, apiKey);
        return setup;
    }

    @Override
    public ProbeResult probe(ProbeCommand command) {
        Objects.requireNonNull(command, "command");
        Provider provider = requireProvider(command.providerId());
        if (provider.managed()) {
            throw new ValidationException("Deliverance 档案必须通过实际加载探测，不能使用 HTTP 连接测试");
        }
        String baseUrl = required(command.baseUrl(), "请先填写 Base URL");
        URI probeUri = probeUri(provider, baseUrl);
        try {
            return new ProbeResult(connection.status(probeUri));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new CancellationException("连接测试已取消");
        } catch (IOException | RuntimeException failure) {
            throw new RejectedException("连接失败，请检查 Base URL 是否正确", failure);
        }
    }

    @Override
    public void complete() {
        if (!settings.completed()) settings.markCompleted();
    }

    private static URI probeUri(Provider provider, String baseUrl) {
        String trimmed = baseUrl.trim();
        String target = provider.local()
                ? trimmed.replaceAll("/v1/?$", "") + "/api/tags"
                : trimmed.replaceAll("/+$", "") + "/models";
        return validateHttpUri(target);
    }

    private static URI validateHttpUri(String text) {
        try {
            URI uri = URI.create(text);
            if (uri.getHost() == null
                    || !("http".equalsIgnoreCase(uri.getScheme())
                    || "https".equalsIgnoreCase(uri.getScheme()))) {
                throw new IllegalArgumentException("只支持 HTTP(S) 地址");
            }
            return uri;
        } catch (IllegalArgumentException invalid) {
            throw new ValidationException("Base URL 格式不正确");
        }
    }

    private Provider requireProvider(String id) {
        String normalized = catalog.normalizeId(id);
        return providers.stream()
                .filter(provider -> catalog.normalizeId(provider.id()).equals(normalized))
                .findFirst()
                .orElseThrow(() -> new ValidationException("请选择模型提供商"));
    }

    private Provider onboardingProvider(ModelProviderCatalog.Provider provider) {
        boolean managed = provider.localManaged();
        boolean local = managed || "ollama".equals(provider.id());
        boolean recommended = managed || "dashscope".equals(provider.id());
        // 首启向导的 Provider.id 是历史公开契约；目录和持久化层仍统一使用稳定小写 ID。
        String compatibilityId = provider.localManaged() ? provider.id() : provider.displayName();
        return new Provider(compatibilityId, provider.displayName(), provider.description(),
                provider.defaultBaseUrl(), provider.defaultChatModel(), local, managed, recommended);
    }

    private static String required(String value, String message) {
        if (value == null || value.isBlank()) throw new ValidationException(message);
        return value.trim();
    }
}
