package com.javaclaw.sdk.model;

import java.time.Instant;

/**
 * 人工登录的非敏感会话引用。
 *
 * @param sessionId 服务端会话标识
 * @param siteId 所属站点
 * @param expiresAt 到期时间
 */
public record BrowserLoginInfo(String sessionId, String siteId, Instant expiresAt) {}
