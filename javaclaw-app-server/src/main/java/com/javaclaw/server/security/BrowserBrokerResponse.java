package com.javaclaw.server.security;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 仅能写入隔离 Browser Worker 私有管道的单跳 HTTP 响应。
 *
 * <p>Header 可能包含 {@code Set-Cookie}，调用方不得记录、投影为 RPC/Artifact 或交给非 Browser 代码。正文 accessor 返回副本，所有权不会转移。
 *
 * @param statusCode HTTP 状态码
 * @param headers 仅供 Browser 的响应头
 * @param body 有界原始正文
 * @param truncated 正文是否达到上限
 */
public record BrowserBrokerResponse(int statusCode, Map<String, List<String>> headers, byte[] body, boolean truncated) {
    /** 深复制敏感响应。 */
    public BrowserBrokerResponse {
        if (statusCode < 100 || statusCode > 599) {
            throw new IllegalArgumentException("invalid HTTP status code");
        }
        headers = Objects.requireNonNull(headers, "headers").entrySet().stream()
                .collect(java.util.stream.Collectors.toUnmodifiableMap(
                        Map.Entry::getKey, entry -> List.copyOf(entry.getValue())));
        body = Objects.requireNonNull(body, "body").clone();
    }

    /** 返回正文副本。 */
    @Override
    public byte[] body() {
        return body.clone();
    }
}
