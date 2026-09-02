package com.javaclaw.api;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Network Broker 返回的有界响应。
 *
 * @param statusCode HTTP 状态码
 * @param headers 已脱敏响应头
 * @param body 响应体；所有权由本对象持有
 * @param truncated 是否达到大小上限
 */
public record BrokerResponse(int statusCode, Map<String, List<String>> headers, byte[] body, boolean truncated) {
    /** 复制响应并校验状态码。 */
    public BrokerResponse {
        if (statusCode < 100 || statusCode > 599) {
            throw new IllegalArgumentException("invalid HTTP status code");
        }
        headers = Objects.requireNonNull(headers, "headers").entrySet().stream()
                .collect(java.util.stream.Collectors.toUnmodifiableMap(
                        entry -> Objects.requireNonNull(entry.getKey(), "header name"),
                        entry -> List.copyOf(entry.getValue())));
        body = Objects.requireNonNull(body, "body").clone();
    }

    @Override
    public byte[] body() {
        return body.clone();
    }
}
