package com.javaclaw.server.extension.mcp;

/**
 * Persisted MCP state plus the latest capability discovery snapshot.
 *
 * @param id 资源或声明的稳定标识
 * @param revision 持久修订号，用于乐观锁和缓存失效
 * @param enabled 是否允许新调用使用该资源；禁用不删除历史
 * @param state 持久生命周期状态
 * @param discovery 最近成功发现结果；尚未成功或已失效时可为 null
 */
public record McpDiscoveryStatus(String id, long revision, boolean enabled, String state, McpDiscovery discovery) {}
