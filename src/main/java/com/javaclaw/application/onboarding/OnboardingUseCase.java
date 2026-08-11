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

/** 首次向导校验、保存与连接探测用例。 */
public final class OnboardingUseCase implements OnboardingApplicationService {

    private static final List<Provider> PROVIDERS = List.of(
            new Provider("DashScope", "阿里通义千问", "阿里云 DashScope，中文场景推荐",
                    "https://dashscope.aliyuncs.com/compatible-mode/v1", "qwen-plus", false, true),
            new Provider("OpenAI", "OpenAI / 兼容 API",
                    "GPT、DeepSeek、GLM、LMStudio 等 OpenAI 兼容接口",
                    "https://api.openai.com/v1", "gpt-4o-mini", false, false),
            new Provider("Anthropic", "Anthropic Claude", "Claude 4 系列，推理与长上下文强",
                    "https://api.anthropic.com", "claude-sonnet-4-6", false, false),
            new Provider("Gemini", "Google Gemini", "Gemini 2.x 系列，多模态",
                    "https://generativelanguage.googleapis.com", "gemini-2.0-flash", false, false),
            new Provider("Ollama", "Ollama（本地）", "本地模型，隐私敏感场景推荐，无需 API Key",
                    "http://localhost:11434/v1", "qwen2.5:7b", true, true));

    private final OnboardingSettingsPort settings;
    private final ConnectionProbePort connection;

    public OnboardingUseCase(
            OnboardingSettingsPort settings,
            ConnectionProbePort connection) {
        this.settings = Objects.requireNonNull(settings, "settings");
        this.connection = Objects.requireNonNull(connection, "connection");
    }

    @Override
    public boolean required() {
        return !settings.completed();
    }

    @Override
    public List<Provider> providers() {
        return PROVIDERS;
    }

    @Override
    public ProviderSetup save(ProviderSetupCommand command) {
        Objects.requireNonNull(command, "command");
        Provider provider = requireProvider(command.providerId());
        String baseUrl = required(command.baseUrl(), "Base URL 不能为空");
        validateHttpUri(baseUrl);
        String model = required(command.modelName(), "模型名称不能为空");
        String apiKey = provider.local()
                ? "not-needed" : required(command.apiKey(), "云端模型需要填写 API Key");
        ProviderSetup setup = new ProviderSetup(provider, baseUrl, model);
        settings.save(setup, apiKey);
        return setup;
    }

    @Override
    public ProbeResult probe(ProbeCommand command) {
        Objects.requireNonNull(command, "command");
        Provider provider = requireProvider(command.providerId());
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

    private static Provider requireProvider(String id) {
        return PROVIDERS.stream()
                .filter(provider -> provider.id().equals(id))
                .findFirst()
                .orElseThrow(() -> new ValidationException("请选择模型提供商"));
    }

    private static String required(String value, String message) {
        if (value == null || value.isBlank()) throw new ValidationException(message);
        return value.trim();
    }
}
