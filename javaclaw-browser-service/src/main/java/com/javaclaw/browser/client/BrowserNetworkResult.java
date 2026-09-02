package com.javaclaw.browser.client;

import java.util.Objects;

import com.javaclaw.browser.protocol.BrowserWorkerProtocol;

/**
 * 只在 App Server 与隔离 Worker 管道之间流转的 Browser 网络响应。
 *
 * @param response HTTP 响应元数据；可能含 Cookie header，禁止日志与 RPC
 * @param body 原始响应 body
 */
public record BrowserNetworkResult(BrowserWorkerProtocol.NetworkResponse response, byte[] body) {
    /** 复制 body 并校验依赖。 */
    public BrowserNetworkResult {
        Objects.requireNonNull(response, "response");
        body = Objects.requireNonNull(body, "body").clone();
        if (body.length > BrowserWorkerProtocol.MAXIMUM_NETWORK_BYTES) {
            throw new IllegalArgumentException("Browser response exceeds the Worker frame limit");
        }
    }

    @Override
    public byte[] body() {
        return body.clone();
    }
}
