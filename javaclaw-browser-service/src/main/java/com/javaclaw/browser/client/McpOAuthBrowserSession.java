package com.javaclaw.browser.client;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * 隔离 OAuth 浏览器的非敏感会话状态。
 *
 * @param sessionId Worker 控制 UUID
 * @param authorizationId 服务端 OAuth 流程标识
 * @param endpointId Endpoint 标识
 * @param endpointRevision 冻结 revision
 * @param state 当前状态
 * @param startedAt 启动时间
 * @param expiresAt 硬截止时间
 * @param failureCode 稳定错误码；仅 FAILED 存在
 */
public record McpOAuthBrowserSession(
        String sessionId,
        String authorizationId,
        String endpointId,
        long endpointRevision,
        McpOAuthBrowserState state,
        Instant startedAt,
        Instant expiresAt,
        Optional<String> failureCode) {
    /** 校验非敏感投影。 */
    public McpOAuthBrowserSession {
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(authorizationId, "authorizationId");
        Objects.requireNonNull(endpointId, "endpointId");
        if (endpointRevision < 1) {
            throw new IllegalArgumentException("endpointRevision must be positive");
        }
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(startedAt, "startedAt");
        Objects.requireNonNull(expiresAt, "expiresAt");
        failureCode = Objects.requireNonNull(failureCode, "failureCode");
        if (failureCode.isPresent() != (state == McpOAuthBrowserState.FAILED)) {
            throw new IllegalArgumentException("failureCode is required only for FAILED state");
        }
    }
}
