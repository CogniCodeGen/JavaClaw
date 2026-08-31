package com.javaclaw.sandbox.api;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable response metadata exposed before a brokered response body is consumed.
 *
 * @param statusCode HTTP 响应状态码
 * @param finalUri 完成重定向校验后的最终 URI，非空
 * @param headers HTTP 头快照；null 归一为空 Map
 * @param redirectCount 已跟随的重定向次数
 */
public record BrokerResponseHead(int statusCode, URI finalUri, Map<String, List<String>> headers, int redirectCount) {
    /** 固定最终 URI 和头 Map；正文由 Exchange 单独按预算消费。 */
    public BrokerResponseHead {
        if (statusCode < 100 || statusCode > 599) {
            throw new IllegalArgumentException("invalid HTTP status");
        }
        finalUri = Objects.requireNonNull(finalUri, "finalUri");
        LinkedHashMap<String, List<String>> copy = new LinkedHashMap<>();
        if (headers != null) {
            headers.forEach((name, values) -> copy.put(
                    Objects.requireNonNull(name, "header name"),
                    List.copyOf(Objects.requireNonNull(values, "header values"))));
        }
        headers = Map.copyOf(copy);
        if (redirectCount < 0) {
            throw new IllegalArgumentException("redirectCount is negative");
        }
    }
}
