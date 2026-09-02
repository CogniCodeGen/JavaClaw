package com.javaclaw.builtin.contracts;

import java.net.URI;
import java.time.Duration;
import java.util.Objects;
import java.util.Set;

import com.javaclaw.api.PrivateNetworkGrant;

/** Site 契约共用的有限输入与网络地址校验。 */
final class SiteContractValidation {
    private SiteContractValidation() {}

    static URI normalizeOrigin(URI value) {
        return PrivateNetworkGrant.normalizeOrigin(value);
    }

    static URI requireHttpsPage(URI value, String name) {
        URI uri = Objects.requireNonNull(value, name).normalize();
        if (!uri.isAbsolute()
                || !"https".equalsIgnoreCase(uri.getScheme())
                || uri.getHost() == null
                || uri.getUserInfo() != null
                || uri.getFragment() != null
                || uri.getPort() == 0
                || uri.getPort() < -1
                || uri.getPort() > 65_535) {
            throw new IllegalArgumentException(name + " must be an HTTPS URI with no user info or fragment");
        }
        return uri;
    }

    static void requireAllowed(URI uri, Set<URI> allowedOrigins) {
        if (!allowedOrigins.contains(SiteContracts.originOf(uri))) {
            throw new IllegalArgumentException("uri origin is outside the Site allowlist");
        }
    }

    static int maximumCharacters(int value) {
        if (value < 1 || value > 200_000) {
            throw new IllegalArgumentException("maxCharacters must be between 1 and 200000");
        }
        return value;
    }

    static Duration checkedTimeout(Duration value, Duration maximum) {
        Duration checked = Objects.requireNonNull(value, "timeout");
        if (checked.compareTo(Duration.ofSeconds(1)) < 0 || checked.compareTo(maximum) > 0) {
            throw new IllegalArgumentException("timeout is outside the allowed range");
        }
        return checked;
    }

    static String uuid(String value, String name) {
        String checked = boundedText(value, name, 36);
        try {
            return java.util.UUID.fromString(checked).toString();
        } catch (IllegalArgumentException failure) {
            throw new IllegalArgumentException(name + " must be a UUID", failure);
        }
    }

    static String boundedText(String value, String name, int maximum) {
        String checked = ContractValidation.text(value, name);
        if (checked.length() > maximum) {
            throw new IllegalArgumentException(name + " exceeds its length limit");
        }
        return checked;
    }

    static String sha256(String value, String name) {
        String checked = boundedText(value, name, 64).toLowerCase(java.util.Locale.ROOT);
        if (!checked.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(name + " must be SHA-256 hex");
        }
        return checked;
    }

    static String errorCode(String value, String name) {
        String checked = boundedText(value, name, 80);
        if (!checked.matches("[A-Z][A-Z0-9_]{0,79}")) {
            throw new IllegalArgumentException(name + " must be a stable error code");
        }
        return checked;
    }
}
