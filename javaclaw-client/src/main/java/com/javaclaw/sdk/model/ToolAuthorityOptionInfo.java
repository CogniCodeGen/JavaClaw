package com.javaclaw.sdk.model;

/**
 * 当前工作区最近真实发现的工具；没有发现数据时不能凭猜测创建许可。
 *
 * @param sourceId MCP 连接
 * @param toolName 公开工具名
 * @param description 外部工具说明
 * @param sourceRevision 连接版本
 * @param schemaSha256 Schema 指纹
 * @param inputSchema 输入契约
 */
public record ToolAuthorityOptionInfo(
        String sourceId,
        String toolName,
        String description,
        long sourceRevision,
        String schemaSha256,
        JsonDocument inputSchema) {}
