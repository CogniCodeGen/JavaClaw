package com.javaclaw.server.extension;

/**
 * 最近一次真实发现的可授权 MCP 工具，不触发网络访问或承诺当前可执行。
 *
 * @param sourceId MCP 连接标识
 * @param toolName 稳定公开工具名
 * @param description 工具说明，属于外部数据
 * @param sourceRevision 连接配置版本
 * @param schemaSha256 输入 Schema 指纹
 * @param inputSchemaJson 原始输入 Schema，用于明确展示权限范围
 */
public record ToolAuthorityOption(
        String sourceId,
        String toolName,
        String description,
        long sourceRevision,
        String schemaSha256,
        String inputSchemaJson) {}
