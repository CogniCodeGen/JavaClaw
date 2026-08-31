package com.javaclaw.sdk.model;

import java.time.Instant;
import java.util.Set;

/**
 * 无人值守通信许可；修改不重置已用次数，所有保存必须由用户明确确认。
 *
 * @param id 授权标识，新建允许空
 * @param workspaceId 工作区
 * @param sourceId MCP 连接
 * @param toolName 公开工具名
 * @param sourceRevision 连接版本
 * @param schemaSha256 Schema 指纹
 * @param argumentTemplate 用户确认的完整参数模板，不得含秘密
 * @param recipientField 固定接收字段
 * @param variableFields 可变文本字段
 * @param maximumUses 总次数上限
 * @param consumedUses 已核销次数，只读
 * @param expiresAt 到期时间
 * @param enabled 是否启用
 * @param revision 乐观锁版本
 * @param updatedAt 更新时间
 */
public record ToolAuthorizationInfo(
        String id,
        String workspaceId,
        String sourceId,
        String toolName,
        long sourceRevision,
        String schemaSha256,
        JsonDocument argumentTemplate,
        String recipientField,
        Set<String> variableFields,
        int maximumUses,
        int consumedUses,
        Instant expiresAt,
        boolean enabled,
        long revision,
        Instant updatedAt) {
    /** 复制可变字段集合，保持表单提交后快照不可变。 */
    public ToolAuthorizationInfo {
        variableFields = Set.copyOf(variableFields == null ? Set.of() : variableFields);
    }
}
