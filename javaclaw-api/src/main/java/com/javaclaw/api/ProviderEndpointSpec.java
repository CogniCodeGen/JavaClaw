package com.javaclaw.api;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Provider 端点中可由用户修改的完整配置。
 *
 * @param displayName 用户可见名称
 * @param adapter 适配器类型
 * @param baseUri 可选兼容端点；官方默认地址时为空
 * @param roles 端点角色
 * @param models 可选择的 Provider 原生模型名
 * @param credential Vault 凭据引用；不含 Secret 值
 * @param timeout 单次调用超时
 * @param maximumRetries 最大重试次数
 * @param options 受适配器校验的非敏感选项
 */
public record ProviderEndpointSpec(
        String displayName,
        ProviderAdapter adapter,
        Optional<URI> baseUri,
        Set<ProviderRole> roles,
        List<String> models,
        Optional<CredentialRef> credential,
        Duration timeout,
        int maximumRetries,
        Map<String, String> options) {
    /** 复制集合并校验非敏感配置。 */
    public ProviderEndpointSpec {
        displayName = Preconditions.text(displayName, "displayName");
        Objects.requireNonNull(adapter, "adapter");
        baseUri = Objects.requireNonNull(baseUri, "baseUri");
        baseUri.ifPresent(ProviderEndpointSpec::requireSafeBaseUri);
        roles = Set.copyOf(roles);
        if (roles.isEmpty()) {
            throw new IllegalArgumentException("roles must not be empty");
        }
        models = models.stream()
                .map(value -> Preconditions.text(value, "model"))
                .distinct()
                .toList();
        if (models.isEmpty()) {
            throw new IllegalArgumentException("models must not be empty");
        }
        credential = Objects.requireNonNull(credential, "credential");
        credential.ifPresent(reference -> {
            if (!"provider".equals(reference.namespace())) {
                throw new IllegalArgumentException("Provider credential namespace must be provider");
            }
        });
        timeout = Objects.requireNonNull(timeout, "timeout");
        if (timeout.isZero() || timeout.isNegative() || timeout.compareTo(Duration.ofMinutes(10)) > 0) {
            throw new IllegalArgumentException("timeout must be between 1 nanosecond and 10 minutes");
        }
        if (maximumRetries < 0 || maximumRetries > 10) {
            throw new IllegalArgumentException("maximumRetries must be between 0 and 10");
        }
        options = Map.copyOf(options);
        options.forEach((key, value) -> {
            Preconditions.identifier(key, "option key");
            Objects.requireNonNull(value, "option value");
            requireNonSensitiveOption(key);
        });
    }

    private static void requireNonSensitiveOption(String key) {
        String normalized = key.toLowerCase(java.util.Locale.ROOT).replaceAll("[._-]", "");
        if (normalized.contains("apikey")
                || normalized.contains("authorization")
                || normalized.contains("cookie")
                || normalized.contains("password")
                || normalized.contains("privatekey")
                || normalized.contains("secret")
                || normalized.contains("token")) {
            throw new IllegalArgumentException("options must not contain credential material");
        }
    }

    private static void requireSafeBaseUri(URI uri) {
        if (!uri.isAbsolute() || uri.getUserInfo() != null || uri.getFragment() != null) {
            throw new IllegalArgumentException("baseUri must be absolute and must not contain credentials or fragment");
        }
        String scheme = Objects.requireNonNullElse(uri.getScheme(), "");
        if (!scheme.equalsIgnoreCase("https") && !scheme.equalsIgnoreCase("http")) {
            throw new IllegalArgumentException("baseUri scheme must be HTTP or HTTPS");
        }
    }
}
