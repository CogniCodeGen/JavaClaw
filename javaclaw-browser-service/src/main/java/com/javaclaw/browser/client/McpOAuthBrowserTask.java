package com.javaclaw.browser.client;

import java.net.URI;
import java.time.Duration;
import java.util.Objects;
import java.util.Set;

import com.javaclaw.browser.protocol.BrowserWorkerProtocol;

/**
 * App Server 交给隔离 Browser Worker 的 OAuth 私有任务。
 *
 * @param sessionId 随机 UUID，只用于 Worker 控制通道
 * @param authorizationId 服务端 OAuth 流程标识
 * @param endpointId 冻结 Endpoint 标识
 * @param endpointRevision 冻结 Endpoint revision
 * @param authorizationUri 含 state/challenge 的完整授权 URI；禁止进入 RPC/日志
 * @param allowedOrigins metadata 允许的精确 HTTPS Origin
 * @param redirectUri 只允许 Worker 本地截获的精确 loopback URI
 * @param timeout 会话硬时限，最多十分钟
 */
public record McpOAuthBrowserTask(
        String sessionId,
        String authorizationId,
        String endpointId,
        long endpointRevision,
        URI authorizationUri,
        Set<URI> allowedOrigins,
        URI redirectUri,
        Duration timeout) {
    /** 复用私有 wire 契约执行完整边界校验。 */
    public McpOAuthBrowserTask {
        BrowserWorkerProtocol.OAuthTask checked = new BrowserWorkerProtocol.OAuthTask(
                sessionId,
                authorizationId,
                endpointId,
                endpointRevision,
                authorizationUri,
                allowedOrigins,
                redirectUri,
                timeout);
        sessionId = checked.sessionId();
        authorizationId = checked.authorizationId();
        endpointId = checked.endpointId();
        endpointRevision = checked.endpointRevision();
        authorizationUri = checked.authorizationUri();
        allowedOrigins = checked.allowedOrigins();
        redirectUri = checked.redirectUri();
        timeout = checked.timeout();
        Objects.requireNonNull(timeout, "timeout");
    }
}
