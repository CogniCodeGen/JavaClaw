package com.javaclaw.api;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Network Broker 的受控 HTTP 请求。
 *
 * @param uri 仅允许 http 或 https，禁止 user-info
 * @param method 大写 HTTP 方法
 * @param headers 已经过滤的请求头
 * @param body 请求体；所有权由本对象持有
 * @param maximumResponseBytes 最大响应字节数
 * @param timeout 总超时
 */
public record BrokerRequest(
        URI uri,
        String method,
        Map<String, List<String>> headers,
        byte[] body,
        long maximumResponseBytes,
        Duration timeout) {
    private static final Set<String> METHODS = Set.of("GET", "HEAD", "POST", "PUT", "PATCH", "DELETE", "OPTIONS");
    private static final Pattern HEADER_NAME = Pattern.compile("[!#$%&'*+.^_`|~0-9A-Za-z-]{1,80}");

    /** 校验 URI、大小和超时并复制内容。 */
    public BrokerRequest {
        uri = Objects.requireNonNull(uri, "uri").normalize();
        if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))) {
            throw new IllegalArgumentException("uri scheme must be http or https");
        }
        if (!uri.isAbsolute() || uri.getHost() == null || uri.getUserInfo() != null || uri.getFragment() != null) {
            throw new IllegalArgumentException("uri must be absolute with a host and without user-info or fragment");
        }
        method = Preconditions.text(method, "method").toUpperCase(Locale.ROOT);
        if (!METHODS.contains(method)) {
            throw new IllegalArgumentException("unsupported HTTP method: " + method);
        }
        headers = Objects.requireNonNull(headers, "headers").entrySet().stream()
                .collect(java.util.stream.Collectors.toUnmodifiableMap(
                        entry -> headerName(entry.getKey()), entry -> headerValues(entry.getValue())));
        body = Objects.requireNonNull(body, "body").clone();
        maximumResponseBytes = Preconditions.positive(maximumResponseBytes, "maximumResponseBytes");
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("timeout must be positive");
        }
    }

    @Override
    public byte[] body() {
        return body.clone();
    }

    private static String headerName(String value) {
        String name = Objects.requireNonNull(value, "header name").strip().toLowerCase(Locale.ROOT);
        if (!HEADER_NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("invalid HTTP header name");
        }
        return name;
    }

    private static List<String> headerValues(List<String> source) {
        return Objects.requireNonNull(source, "header values").stream()
                .map(value -> {
                    String checked = Objects.requireNonNull(value, "header value");
                    if (checked.length() > 8_192
                            || checked.chars()
                                    .anyMatch(character -> character == '\r'
                                            || character == '\n'
                                            || character == 0
                                            || character < 0x20 && character != '\t')) {
                        throw new IllegalArgumentException("invalid HTTP header value");
                    }
                    return checked;
                })
                .toList();
    }
}
