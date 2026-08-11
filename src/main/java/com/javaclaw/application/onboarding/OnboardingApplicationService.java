package com.javaclaw.application.onboarding;

import java.util.List;

/**
 * 首次启动向导的应用入口。
 *
 * <p>实例属于根 Spring Context，可并发调用。保存和完成操作访问当前工作区配置，连接探测
 * 会阻塞等待外部 HTTP，因此调用方必须在托管 I/O 任务中执行。保存会完整替换向导涉及的
 * 模型连接字段；{@link #complete()} 幂等。</p>
 */
public interface OnboardingApplicationService {

    boolean required();

    List<Provider> providers();

    ProviderSetup save(ProviderSetupCommand command);

    ProbeResult probe(ProbeCommand command);

    void complete();

    record Provider(
            String id,
            String displayName,
            String description,
            String baseUrl,
            String defaultModel,
            boolean local,
            boolean recommended) {

        public Provider {
            id = requiredText(id, "provider id");
            displayName = requiredText(displayName, "provider displayName");
            description = text(description);
            baseUrl = requiredText(baseUrl, "provider baseUrl");
            defaultModel = requiredText(defaultModel, "provider defaultModel");
        }
    }

    record ProviderSetupCommand(
            String providerId,
            String baseUrl,
            String modelName,
            String apiKey) {}

    record ProviderSetup(
            Provider provider,
            String baseUrl,
            String modelName) {}

    record ProbeCommand(String providerId, String baseUrl) {}

    record ProbeResult(int statusCode) {
        public ProbeResult {
            if (statusCode < 100 || statusCode > 599) {
                throw new IllegalArgumentException("HTTP 状态码超出范围");
            }
        }
    }

    private static String requiredText(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + " 不能为空");
        }
        return value;
    }

    private static String text(String value) {
        return value == null ? "" : value;
    }
}
