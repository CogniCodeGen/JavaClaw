package com.javaclaw.sandbox.api;

import java.net.URI;
import java.util.List;
import java.util.Map;

/**
 * Fully bounded response returned by the broker.
 *
 * @param statusCode HTTP 响应状态码
 * @param finalUri 完成重定向校验后的最终 URI，非空
 * @param headers HTTP 头快照；null 归一为空 Map
 * @param body 请求或响应字节；null 归一为空数组，输入和读取均防御性复制
 * @param redirectCount 已跟随的重定向次数
 */
public record BrokerResponse(
        int statusCode, URI finalUri, Map<String, List<String>> headers, byte[] body, int redirectCount) {
    /** 复制最终响应体和头 Map，防止调用方更改已完成交换的结果。 */
    public BrokerResponse {
        if (statusCode < 100 || statusCode > 599) {
            throw new IllegalArgumentException("invalid HTTP status");
        }
        headers = headers == null ? Map.of() : Map.copyOf(headers);
        body = body == null ? new byte[0] : body.clone();
        if (redirectCount < 0) {
            throw new IllegalArgumentException("redirectCount is negative");
        }
    }

    @Override
    public byte[] body() {
        return body.clone();
    }
}
