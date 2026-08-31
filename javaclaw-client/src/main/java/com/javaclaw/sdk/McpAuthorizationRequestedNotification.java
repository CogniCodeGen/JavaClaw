package com.javaclaw.sdk;

import java.net.URI;
import java.time.Instant;

/**
 * 通知客户端打开一次性 MCP 授权 URL；不传递 token 或 client secret。
 *
 * @param mcpId MCP Server 配置标识
 * @param authorizationId 一次性 OAuth 授权会话标识
 * @param authorizationUrl 供用户打开的授权 URL；不含访问或刷新 token
 * @param expiresAt 上传或授权会话的过期时间
 */
public record McpAuthorizationRequestedNotification(
        String mcpId, String authorizationId, URI authorizationUrl, Instant expiresAt) implements ClientNotification {}
