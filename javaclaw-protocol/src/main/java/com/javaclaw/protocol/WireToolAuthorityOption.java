package com.javaclaw.protocol;

/**
 * 真实发现的可授权工具，不表示签名或发现自动授予执行权。
 *
 * @param sourceId MCP 连接
 * @param toolName 公开工具名
 * @param description 外部工具说明
 * @param sourceRevision 连接版本
 * @param schemaSha256 Schema 指纹
 * @param inputSchemaJson 输入 Schema JSON
 */
public record WireToolAuthorityOption(
        String sourceId,
        String toolName,
        String description,
        long sourceRevision,
        String schemaSha256,
        String inputSchemaJson) {}
