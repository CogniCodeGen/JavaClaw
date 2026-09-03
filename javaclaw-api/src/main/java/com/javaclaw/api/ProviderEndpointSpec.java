package com.javaclaw.api;

import java.net.URI;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Provider 端点中可由用户修改的完整配置。
 *
 * @param displayName 用户可见名称
 * @param adapter 适配器类型
 * @param baseUri 可选兼容端点；官方默认地址时为空
 * @param authentication 鉴权方式
 * @param models 逐模型用途配置；禁用连接壳允许为空
 * @param credential Vault 凭据引用；不含 Secret 值
 * @param timeout 单次调用超时
 * @param maximumRetries 最大重试次数
 * @param options 与 Adapter 对应的强类型非敏感选项
 */
public record ProviderEndpointSpec(
        String displayName,
        ProviderAdapter adapter,
        Optional<URI> baseUri,
        ProviderAuthentication authentication,
        List<ProviderModelSpec> models,
        Optional<CredentialRef> credential,
        Duration timeout,
        int maximumRetries,
        ProviderAdapterOptions options) {
    /** 复制集合并校验非敏感配置。 */
    public ProviderEndpointSpec {
        displayName = Preconditions.boundedText(displayName, "displayName", 1_000);
        Objects.requireNonNull(adapter, "adapter");
        Objects.requireNonNull(authentication, "authentication");
        baseUri = Objects.requireNonNull(baseUri, "baseUri");
        baseUri.ifPresent(uri -> validateBaseUri(adapter, authentication, uri));
        models = List.copyOf(Objects.requireNonNull(models, "models"));
        if (models.size() > 1_000) {
            throw new IllegalArgumentException("models must not exceed 1000 entries");
        }
        requireUniqueModels(models);
        requireSupportedPurposes(adapter, models);
        credential = Objects.requireNonNull(credential, "credential");
        credential.ifPresent(reference -> {
            if (!"provider".equals(reference.namespace())) {
                throw new IllegalArgumentException("Provider credential namespace must be provider");
            }
        });
        requireAuthentication(adapter, baseUri, authentication, credential);
        timeout = Objects.requireNonNull(timeout, "timeout");
        if (timeout.isZero() || timeout.isNegative() || timeout.compareTo(Duration.ofMinutes(10)) > 0) {
            throw new IllegalArgumentException("timeout must be between 1 nanosecond and 10 minutes");
        }
        if (maximumRetries < 0 || maximumRetries > 10) {
            throw new IllegalArgumentException("maximumRetries must be between 0 and 10");
        }
        Objects.requireNonNull(options, "options");
        if (!matches(adapter, options)) {
            throw new IllegalArgumentException("Provider options adapter must match endpoint adapter");
        }
    }

    private static void requireUniqueModels(List<ProviderModelSpec> models) {
        HashSet<String> modelIds = new HashSet<>();
        if (models.stream().anyMatch(model -> !modelIds.add(model.modelId()))) {
            throw new IllegalArgumentException("models must not contain duplicate model IDs");
        }
    }

    private static boolean matches(ProviderAdapter adapter, ProviderAdapterOptions options) {
        return switch (adapter) {
            case OPENAI_COMPATIBLE -> options instanceof ProviderAdapterOptions.OpenAiCompatible;
            case ANTHROPIC -> options instanceof ProviderAdapterOptions.Anthropic;
            case GOOGLE_GENAI -> options instanceof ProviderAdapterOptions.GoogleGenAi;
            case OPENAI_RESPONSES -> options instanceof ProviderAdapterOptions.OpenAiResponses;
        };
    }

    private static void requireSupportedPurposes(ProviderAdapter adapter, List<ProviderModelSpec> models) {
        if ((adapter == ProviderAdapter.ANTHROPIC || adapter == ProviderAdapter.OPENAI_RESPONSES)
                && models.stream().anyMatch(model -> model.supports(ProviderModelPurpose.EMBEDDING))) {
            throw new IllegalArgumentException(adapter + " only supports CHAT models");
        }
    }

    private static void requireAuthentication(
            ProviderAdapter adapter,
            Optional<URI> baseUri,
            ProviderAuthentication authentication,
            Optional<CredentialRef> credential) {
        if (authentication == ProviderAuthentication.NONE) {
            if (adapter != ProviderAdapter.OPENAI_COMPATIBLE || baseUri.isEmpty()) {
                throw new IllegalArgumentException("NONE authentication requires a custom OpenAI-compatible baseUri");
            }
            if (credential.isPresent()) {
                throw new IllegalArgumentException("NONE authentication must not bind a credential");
            }
        }
    }

    /**
     * 校验自定义地址确实是可安全追加资源路径的 API 根地址。
     *
     * @param adapter Provider Adapter
     * @param authentication 鉴权方式；API Key 端点只允许 HTTPS 或显式回环 HTTP
     * @param uri 用户配置的自定义根地址
     * @return 原始地址，便于调用方在校验后继续展示
     */
    public static URI validateBaseUri(ProviderAdapter adapter, ProviderAuthentication authentication, URI uri) {
        Objects.requireNonNull(adapter, "adapter");
        Objects.requireNonNull(authentication, "authentication");
        Objects.requireNonNull(uri, "uri");
        requireAddressShape(uri);
        requireSecureTransport(authentication, uri);
        String normalizedPath = normalizedBasePath(uri);
        requireApiRoot(adapter, normalizedPath);
        return uri;
    }

    private static void requireAddressShape(URI uri) {
        if (uri.toASCIIString().length() > 4_096) {
            throw new IllegalArgumentException("baseUri must not exceed 4096 characters");
        }
        if (!uri.isAbsolute()
                || uri.getHost() == null
                || uri.getUserInfo() != null
                || uri.getQuery() != null
                || uri.getFragment() != null) {
            throw new IllegalArgumentException(
                    "baseUri must have a host and must not contain credentials, query, or fragment");
        }
    }

    private static void requireSecureTransport(ProviderAuthentication authentication, URI uri) {
        String scheme = Objects.requireNonNullElse(uri.getScheme(), "");
        if (!scheme.equalsIgnoreCase("https") && !scheme.equalsIgnoreCase("http")) {
            throw new IllegalArgumentException("baseUri scheme must be HTTP or HTTPS");
        }
        if (authentication == ProviderAuthentication.API_KEY
                && scheme.equalsIgnoreCase("http")
                && !isExplicitLoopback(uri.getHost())) {
            throw new IllegalArgumentException("API_KEY baseUri must use HTTPS unless its host is explicit loopback");
        }
    }

    private static boolean isExplicitLoopback(String host) {
        if (host.equalsIgnoreCase("localhost")) {
            return true;
        }
        String normalized = host.startsWith("[") && host.endsWith("]") ? host.substring(1, host.length() - 1) : host;
        if (normalized.equalsIgnoreCase("::1")) {
            return true;
        }
        String[] octets = normalized.split("\\.", -1);
        if (octets.length != 4 || !octets[0].equals("127")) {
            return false;
        }
        for (String octet : octets) {
            if (!isDecimalOctet(octet)) {
                return false;
            }
        }
        return true;
    }

    private static boolean isDecimalOctet(String value) {
        if (value.isEmpty() || value.length() > 3 || value.length() > 1 && value.charAt(0) == '0') {
            return false;
        }
        int parsed = 0;
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character < '0' || character > '9') {
                return false;
            }
            parsed = parsed * 10 + character - '0';
        }
        return parsed <= 255;
    }

    private static String normalizedBasePath(URI uri) {
        if (!uri.normalize().equals(uri)) {
            throw new IllegalArgumentException("baseUri path must already be normalized");
        }
        String path = Objects.requireNonNullElse(uri.getPath(), "");
        return path.endsWith("/") && path.length() > 1 ? path.substring(0, path.length() - 1) : path;
    }

    private static void requireApiRoot(ProviderAdapter adapter, String normalizedPath) {
        if (isResourcePath(normalizedPath)) {
            throw new IllegalArgumentException("baseUri must be an API root, not a resource endpoint");
        }
        if (adapter == ProviderAdapter.GOOGLE_GENAI && normalizedPath.matches(".*/v[0-9][A-Za-z0-9._-]*")) {
            throw new IllegalArgumentException("Google API version belongs in adapter options, not baseUri");
        }
    }

    private static boolean isResourcePath(String path) {
        return path.endsWith("/models")
                || path.endsWith("/chat/completions")
                || path.endsWith("/embeddings")
                || path.endsWith("/responses")
                || path.endsWith("/responses/compact")
                || path.endsWith("/messages")
                || path.matches(".*/models/[^/]+:(streamGenerateContent|generateContent|embedContent)");
    }
}
