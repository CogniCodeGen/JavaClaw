package com.javaclaw.api;

import java.time.Duration;
import java.util.Objects;
import java.util.Set;

/**
 * 创建无人值守工具授权前由管理界面确认的完整配置。
 *
 * @param workspaceId 所属 Workspace
 * @param scheduleId Schedule 定义标识
 * @param scheduleRevision Schedule revision
 * @param tool 工具精确身份
 * @param catalogRevision 工具目录 revision
 * @param schemaHash 输入 Schema SHA-256
 * @param fixedArguments 固定参数模板
 * @param variableStringFields 申请变化的顶层字符串字段
 * @param maximumUses 最多使用次数
 * @param validity 有效期
 */
public record UnattendedToolGrantDraft(
        WorkspaceId workspaceId,
        String scheduleId,
        long scheduleRevision,
        ToolIdentity tool,
        long catalogRevision,
        String schemaHash,
        CanonicalPayload fixedArguments,
        Set<String> variableStringFields,
        int maximumUses,
        Duration validity) {
    /** 复制集合并校验基本身份；安全字段和期限上限由服务端策略校验。 */
    public UnattendedToolGrantDraft {
        Objects.requireNonNull(workspaceId, "workspaceId");
        scheduleId = Preconditions.identifier(scheduleId, "scheduleId");
        scheduleRevision = Preconditions.positive(scheduleRevision, "scheduleRevision");
        Objects.requireNonNull(tool, "tool");
        catalogRevision = Preconditions.positive(catalogRevision, "catalogRevision");
        schemaHash = Preconditions.digest(schemaHash, "schemaHash");
        Objects.requireNonNull(fixedArguments, "fixedArguments");
        variableStringFields = variableStringFields.stream()
                .map(field -> Preconditions.identifier(field, "variableStringField"))
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        if (maximumUses < 1 || maximumUses > 100) {
            throw new IllegalArgumentException("maximumUses must be between 1 and 100");
        }
        Objects.requireNonNull(validity, "validity");
    }
}
