package com.javaclaw.sdk.model;

import java.net.URI;
import java.time.Instant;

/**
 * 一次 MCP OAuth 授权的公开会话信息，绝不包含 token。
 *
 * @param authorizationId 一次性 OAuth 授权会话标识
 * @param authorizationUrl 供用户打开的授权 URL；不含访问或刷新 token
 * @param expiresAt 上传或授权会话的过期时间
 */
public record McpAuthorizationInfo(String authorizationId, URI authorizationUrl, Instant expiresAt) {}
