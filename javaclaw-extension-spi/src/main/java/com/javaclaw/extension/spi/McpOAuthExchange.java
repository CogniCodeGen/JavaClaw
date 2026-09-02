package com.javaclaw.extension.spi;

import java.net.URI;
import java.util.Objects;

import com.javaclaw.api.CancellationToken;
import com.javaclaw.api.McpEndpoint;

/**
 * OAuth 2.1 token 交换的冻结输入。
 *
 * @param endpoint 授权启动时的精确 Endpoint
 * @param authorizationUri 已持久化的授权 URI，用于恢复公开 client_id
 * @param callbackUri 浏览器回调 URI
 * @param codeVerifier PKCE verifier
 * @param expectedState 期望 CSRF state
 * @param redirectUri 启动时的精确回调 URI
 * @param cancellation 取消信号
 */
public record McpOAuthExchange(
        McpEndpoint endpoint,
        URI authorizationUri,
        URI callbackUri,
        String codeVerifier,
        String expectedState,
        URI redirectUri,
        CancellationToken cancellation) {
    /** 校验所有冻结输入。 */
    public McpOAuthExchange {
        Objects.requireNonNull(endpoint, "endpoint");
        Objects.requireNonNull(authorizationUri, "authorizationUri");
        Objects.requireNonNull(callbackUri, "callbackUri");
        codeVerifier = text(codeVerifier, "codeVerifier");
        expectedState = text(expectedState, "expectedState");
        Objects.requireNonNull(redirectUri, "redirectUri");
        Objects.requireNonNull(cancellation, "cancellation");
    }

    private static String text(String value, String name) {
        String checked = Objects.requireNonNull(value, name).strip();
        if (checked.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return checked;
    }
}
