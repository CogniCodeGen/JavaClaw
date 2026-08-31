package com.javaclaw.sandbox.api;

import java.net.URI;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Bounded HTTP request passed to the App Server's network broker.
 *
 * @param method 非空白 HTTP 方法，归一为大写
 * @param uri 非空请求 URI；目标可达性仍由 NetworkBroker 逐跳检查
 * @param headers HTTP 头快照；null 归一为空 Map
 * @param body 请求或响应字节；null 归一为空数组，输入和读取均防御性复制
 * @param timeout 执行时间上限；null 使用 30 秒，范围为正时长且不超过 5 分钟
 * @param maximumResponseBytes 响应字节预算，必须为正数且不超过 64 MiB
 * @param maximumRedirects 允许跟随的重定向次数，范围 0 到 10
 * @param tlsRequired 是否强制 HTTPS；MCP/OAuth 调用必须为 true
 * @param followRedirects 是否由 Broker 跟随重定向；浏览器为 false，由每一跳重新进入 Broker 的路由处理
 */
public record BrokerRequest(
        String method,
        URI uri,
        Map<String, String> headers,
        byte[] body,
        Duration timeout,
        long maximumResponseBytes,
        int maximumRedirects,
        boolean tlsRequired,
        boolean followRedirects) {
    /** 使用既有跟随语义；MCP/OAuth 仍按 maximumRedirects 限制跳数，默认不改变既有调用行为。 */
    public BrokerRequest(
            String method,
            URI uri,
            Map<String, String> headers,
            byte[] body,
            Duration timeout,
            long maximumResponseBytes,
            int maximumRedirects,
            boolean tlsRequired) {
        this(method, uri, headers, body, timeout, maximumResponseBytes, maximumRedirects, tlsRequired, true);
    }

    /** 创建未强制 TLS 的普通 Broker 请求；MCP/OAuth 必须使用显式 tlsRequired 的构造器。 */
    public BrokerRequest(
            String method,
            URI uri,
            Map<String, String> headers,
            byte[] body,
            Duration timeout,
            long maximumResponseBytes,
            int maximumRedirects) {
        this(method, uri, headers, body, timeout, maximumResponseBytes, maximumRedirects, false);
    }

    /** 复制请求体并校验方法、头格式及资源预算；SSRF、凭据授权和重定向检查由 Broker 执行。 */
    public BrokerRequest {
        method = Objects.requireNonNull(method, "method").strip().toUpperCase(Locale.ROOT);
        if (!method.matches("GET|HEAD|POST|PUT|PATCH|DELETE|OPTIONS")) {
            throw new IllegalArgumentException("unsupported broker HTTP method: " + method);
        }
        uri = Objects.requireNonNull(uri, "uri");
        LinkedHashMap<String, String> safeHeaders = new LinkedHashMap<>();
        if (headers != null) {
            headers.forEach((name, value) -> {
                String normalizedName =
                        Objects.requireNonNull(name, "header name").strip();
                String normalizedValue =
                        Objects.requireNonNull(value, "header value").strip();
                if (!normalizedName.matches("[!#$%&'*+.^_`|~0-9A-Za-z-]+")
                        || normalizedValue.indexOf('\r') >= 0
                        || normalizedValue.indexOf('\n') >= 0) {
                    throw new IllegalArgumentException("invalid broker HTTP header");
                }
                if (normalizedName.equalsIgnoreCase("host")
                        || normalizedName.equalsIgnoreCase("content-length")
                        || normalizedName.equalsIgnoreCase("connection")
                        || normalizedName.equalsIgnoreCase("transfer-encoding")) {
                    throw new IllegalArgumentException("broker controls header: " + normalizedName);
                }
                safeHeaders.put(normalizedName, normalizedValue);
            });
        }
        headers = Map.copyOf(safeHeaders);
        body = body == null ? new byte[0] : body.clone();
        timeout = timeout == null ? Duration.ofSeconds(30) : timeout;
        if (timeout.isZero() || timeout.isNegative() || timeout.compareTo(Duration.ofMinutes(5)) > 0) {
            throw new IllegalArgumentException("broker timeout must be between 1ns and 5 minutes");
        }
        if (maximumResponseBytes < 1 || maximumResponseBytes > 64L * 1024L * 1024L) {
            throw new IllegalArgumentException("broker response limit must be 1-67108864 bytes");
        }
        if (maximumRedirects < 0 || maximumRedirects > 10) {
            throw new IllegalArgumentException("broker redirects must be between 0 and 10");
        }
        if (tlsRequired && !"https".equalsIgnoreCase(uri.getScheme())) {
            throw new IllegalArgumentException("TLS-required broker request must use HTTPS");
        }
    }

    @Override
    public byte[] body() {
        return body.clone();
    }
}
