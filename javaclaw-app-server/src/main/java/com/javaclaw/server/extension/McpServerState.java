package com.javaclaw.server.extension;

import java.time.Instant;

/**
 * Persisted MCP configuration metadata represented as canonical JSON text.
 *
 * @param id 资源或声明的稳定标识
 * @param pluginId 所属插件标识；独立 MCP 配置可为空
 * @param name 展示名称
 * @param configurationJson 严格校验的 MCP transport/network/auth 元数据 JSON，不含明文凭据
 * @param enabled 是否允许新调用使用该资源；禁用不删除历史
 * @param state 持久生命周期状态
 * @param revision 持久修订号，用于乐观锁和缓存失效
 * @param updatedAt 最近更新时间；尚未配置的资源可为 null
 */
public record McpServerState(
        String id,
        String pluginId,
        String name,
        String configurationJson,
        boolean enabled,
        String state,
        long revision,
        Instant updatedAt) {}
