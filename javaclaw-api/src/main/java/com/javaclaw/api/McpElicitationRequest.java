package com.javaclaw.api;

import java.time.Instant;
import java.util.Objects;

/**
 * MCP 服务端请求用户提供受 Schema 约束的非敏感输入。
 *
 * @param id 请求标识
 * @param endpointId 来源端点
 * @param prompt 用户可见问题
 * @param responseSchema 受限 JSON Schema
 * @param expiresAt 过期时间
 */
public record McpElicitationRequest(
        String id, String endpointId, String prompt, CanonicalPayload responseSchema, Instant expiresAt) {
    /** 校验输入请求。 */
    public McpElicitationRequest {
        id = Preconditions.identifier(id, "id");
        endpointId = Preconditions.identifier(endpointId, "endpointId");
        prompt = Preconditions.text(prompt, "prompt");
        if (prompt.length() > 1000) {
            throw new IllegalArgumentException("elicitation prompt must not exceed 1000 characters");
        }
        Objects.requireNonNull(responseSchema, "responseSchema");
        Objects.requireNonNull(expiresAt, "expiresAt");
    }
}
