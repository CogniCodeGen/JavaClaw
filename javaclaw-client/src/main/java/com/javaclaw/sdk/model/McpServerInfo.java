package com.javaclaw.sdk.model;

import java.time.Instant;

/**
 * MCP 配置与健康状态的公开视图；凭据只通过 metadata 表达。
 *
 * @param id 服务端资源标识；已保存资源非空
 * @param pluginId 所属插件标识；独立 MCP 配置可为空
 * @param name 展示名称或资源名称；有效服务端响应中非空
 * @param config 经校验的 transport/network/auth 元数据，不含 token 或 client secret
 * @param enabled 是否启用；禁用不会删除历史记录
 * @param state 服务端生命周期状态
 * @param revision 资源修订号，更新时用作 expectedRevision
 * @param updatedAt 最近持久更新时间；已保存资源中非空
 */
public record McpServerInfo(
        String id,
        String pluginId,
        String name,
        JsonDocument config,
        boolean enabled,
        String state,
        long revision,
        Instant updatedAt) {}
