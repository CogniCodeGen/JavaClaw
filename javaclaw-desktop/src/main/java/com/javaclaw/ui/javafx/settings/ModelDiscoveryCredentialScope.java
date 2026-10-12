package com.javaclaw.ui.javafx.settings;

import java.net.URI;
import java.util.Locale;

/** 将模型发现使用的密钥限定在用户配置它时的提供商和 HTTP 来源。 */
final class ModelDiscoveryCredentialScope {
    private String provider = "";
    private String origin = "";

    void capture(String provider, String baseUrl) {
        this.provider = normalizedProvider(provider);
        origin = origin(baseUrl);
    }

    String keyFor(String provider, String baseUrl, String key) {
        return key != null && matches(this.provider, origin, provider, origin(baseUrl))
                ? key : "";
    }

    boolean requiresNewKey(String provider, String baseUrl) {
        String targetOrigin = origin(baseUrl);
        return !targetOrigin.isBlank()
                && !matches(this.provider, origin, provider, targetOrigin);
    }

    static boolean sameEndpoint(String provider, String baseUrl,
            String otherProvider, String otherBaseUrl) {
        return matches(normalizedProvider(provider), origin(baseUrl),
                otherProvider, origin(otherBaseUrl));
    }

    private static boolean matches(String provider, String origin,
            String otherProvider, String otherOrigin) {
        return !provider.isBlank() && !origin.isBlank()
                && provider.equals(normalizedProvider(otherProvider))
                && origin.equals(otherOrigin);
    }

    private static String normalizedProvider(String value) {
        return value == null ? "" : value.strip().toLowerCase(Locale.ROOT);
    }

    private static String origin(String value) {
        if (value == null || value.isBlank()) return "";
        try {
            URI uri = URI.create(value.strip());
            String scheme = uri.getScheme();
            String host = uri.getHost();
            if (scheme == null || host == null
                    || !("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))) {
                return "";
            }
            String normalizedScheme = scheme.toLowerCase(Locale.ROOT);
            int port = uri.getPort() < 0
                    ? ("https".equals(normalizedScheme) ? 443 : 80) : uri.getPort();
            return normalizedScheme + "://" + host.toLowerCase(Locale.ROOT) + ":" + port;
        } catch (IllegalArgumentException failure) {
            return "";
        }
    }
}
