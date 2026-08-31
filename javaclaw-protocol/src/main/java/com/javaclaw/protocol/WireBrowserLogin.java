package com.javaclaw.protocol;

/**
 * 人工登录引用，不携带登录内容。
 *
 * @param sessionId 随机会话标识
 * @param siteId 站点标识
 * @param expiresAt ISO-8601 到期时间
 */
public record WireBrowserLogin(String sessionId, String siteId, String expiresAt) {}
