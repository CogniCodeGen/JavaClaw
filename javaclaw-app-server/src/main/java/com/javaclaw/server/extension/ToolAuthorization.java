package com.javaclaw.server.extension;

import java.time.Instant;
import java.util.Set;

/**
 * 绑定工作区、连接和工具 Schema 的有限预授权；正文仅是用户确认的参数模板，不允许包含凭据。
 *
 * @param id 授权标识；新建请求允许空值
 * @param workspaceId 适用工作区
 * @param sourceId MCP 连接标识
 * @param toolName 公开工具名
 * @param sourceRevision 已确认的连接版本
 * @param schemaSha256 已确认 Schema 的 SHA-256
 * @param argumentTemplate 所有参数的规范 JSON 模板
 * @param recipientField 模板中固定的接收对象字段，不允许成为变量
 * @param variableFields 允许生成内容的顶层文本字段，其余参数必须完全一致
 * @param maximumUses 总次数上限，更新时不能低于已核销次数
 * @param consumedUses 已核销次数
 * @param expiresAt 到期时间
 * @param enabled 是否仍启用
 * @param revision 授权版本，新建为零
 * @param updatedAt 最后修改时间
 */
public record ToolAuthorization(
        String id,
        String workspaceId,
        String sourceId,
        String toolName,
        long sourceRevision,
        String schemaSha256,
        String argumentTemplate,
        String recipientField,
        Set<String> variableFields,
        int maximumUses,
        int consumedUses,
        Instant expiresAt,
        boolean enabled,
        long revision,
        Instant updatedAt) {
    /** 复制变量字段，防止确认后被客户端集合修改。 */
    public ToolAuthorization {
        variableFields = Set.copyOf(variableFields == null ? Set.of() : variableFields);
    }
}
