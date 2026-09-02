package com.javaclaw.browser.worker;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Set;

import com.javaclaw.api.PrivateNetworkGrant;

/** Worker 内部的第二道精确 Origin 白名单。 */
final class BrowserNetworkPolicy {
    private BrowserNetworkPolicy() {}

    static void requireFinalPage(URI uri, Set<URI> allowedOrigins) {
        if (!isHttps(uri) || !allowedOrigins.contains(origin(uri))) {
            throw new IllegalStateException("final page is outside the Browser Worker allowlist");
        }
    }

    static URI requireBrokerTarget(String rawUri, Set<URI> allowedOrigins) {
        URI uri = parse(rawUri);
        if (!isHttps(uri) || !allowedOrigins.contains(origin(uri))) {
            throw new IllegalStateException("Browser request is outside the exact HTTPS Origin allowlist");
        }
        return uri;
    }

    private static boolean isHttps(URI uri) {
        return uri.isAbsolute()
                && "https".equalsIgnoreCase(uri.getScheme())
                && uri.getHost() != null
                && uri.getUserInfo() == null
                && uri.getFragment() == null
                && uri.getPort() != 0
                && uri.getPort() >= -1
                && uri.getPort() <= 65_535;
    }

    private static URI origin(URI uri) {
        try {
            return PrivateNetworkGrant.normalizeOrigin(
                    new URI("https", null, uri.getHost(), uri.getPort(), null, null, null));
        } catch (URISyntaxException failure) {
            throw new IllegalArgumentException("Browser origin is invalid", failure);
        }
    }

    private static URI parse(String value) {
        try {
            return URI.create(value).normalize();
        } catch (IllegalArgumentException failure) {
            return URI.create("blocked://invalid");
        }
    }
}
