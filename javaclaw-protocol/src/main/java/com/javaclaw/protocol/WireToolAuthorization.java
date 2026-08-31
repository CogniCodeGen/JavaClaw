package com.javaclaw.protocol;

import java.util.List;

/**
 * 有限工具授权的协议表示，不含任何 SecretStore 值。
 *
 * @param id 授权标识
 * @param workspaceId 工作区
 * @param sourceId MCP 连接
 * @param toolName 公开工具名
 * @param sourceRevision 连接版本
 * @param schemaSha256 Schema 指纹
 * @param argumentTemplate 用户确认的参数模板 JSON
 * @param recipientField 固定接收对象字段
 * @param variableFields 允许变化的文本字段
 * @param maximumUses 总次数
 * @param consumedUses 已核销次数
 * @param expiresAt ISO-8601 到期时间
 * @param enabled 是否启用
 * @param revision 授权版本
 * @param updatedAt ISO-8601 更新时间
 */
public record WireToolAuthorization(
        String id,
        String workspaceId,
        String sourceId,
        String toolName,
        long sourceRevision,
        String schemaSha256,
        String argumentTemplate,
        String recipientField,
        List<String> variableFields,
        int maximumUses,
        int consumedUses,
        String expiresAt,
        boolean enabled,
        long revision,
        String updatedAt) {}
