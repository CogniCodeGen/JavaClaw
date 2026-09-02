package com.javaclaw.protocol;

import java.util.Objects;
import java.util.regex.Pattern;

/** RPC 名称校验。 */
final class RpcNames {
    private static final Pattern FORMAT = Pattern.compile("[a-z][a-zA-Z0-9]*(?:/[a-z][a-zA-Z0-9]*)+");

    private RpcNames() {}

    static String require(String value) {
        String normalized = Objects.requireNonNull(value, "method").strip();
        if (!FORMAT.matcher(normalized).matches()) {
            throw new IllegalArgumentException("invalid rpc method: " + normalized);
        }
        return normalized;
    }
}
