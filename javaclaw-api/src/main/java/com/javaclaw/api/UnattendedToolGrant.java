package com.javaclaw.api;

import java.time.Instant;
import java.util.Objects;
import java.util.Set;

/**
 * Schedule 可使用的严格限额工具授权不可变快照。
 *
 * <p>固定参数必须是完整规范 JSON；可变字段只能由服务端校验器批准。使用次数由独立调用账本累计，不能修改授权版本。
 *
 * @param id 稳定授权标识
 * @param revision 不可变版本，从 1 开始
 * @param state 生命周期
 * @param workspaceId 所属 Workspace
 * @param scheduleId 固定 Schedule 定义标识
 * @param scheduleRevision 固定 Schedule revision
 * @param tool 固定工具来源、名称与 revision
 * @param catalogRevision 冻结工具目录 revision
 * @param schemaHash 冻结输入 Schema SHA-256
 * @param fixedArguments 完整固定参数模板
 * @param variableStringFields 允许变化的顶层字符串字段
 * @param maximumUses 最多使用次数，范围 1 至 100
 * @param expiresAt 到期时间
 * @param createdAt 首次创建时间
 * @param updatedAt 当前版本写入时间
 */
public record UnattendedToolGrant(
        String id,
        long revision,
        SecurityGrantState state,
        WorkspaceId workspaceId,
        String scheduleId,
        long scheduleRevision,
        ToolIdentity tool,
        long catalogRevision,
        String schemaHash,
        CanonicalPayload fixedArguments,
        Set<String> variableStringFields,
        int maximumUses,
        Instant expiresAt,
        Instant createdAt,
        Instant updatedAt) {
    /** 复制集合并校验身份、限额和时间。 */
    public UnattendedToolGrant {
        id = Preconditions.identifier(id, "id");
        revision = Preconditions.positive(revision, "revision");
        Objects.requireNonNull(state, "state");
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
        Objects.requireNonNull(expiresAt, "expiresAt");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        if (!createdAt.isBefore(expiresAt) || updatedAt.isBefore(createdAt)) {
            throw new IllegalArgumentException("grant timestamps are inconsistent");
        }
    }
}
