package com.javaclaw.api;

import java.util.Objects;

/**
 * Schedule 在执行工具前提交给授权服务的完整冻结身份。
 *
 * @param workspaceId 所属 Workspace
 * @param grantId 使用的授权标识
 * @param grantRevision Turn 冻结的精确授权 revision
 * @param scheduleId Schedule 定义标识
 * @param scheduleRevision Schedule 定义 revision
 * @param tool 工具精确身份
 * @param catalogRevision 工具目录 revision
 * @param schemaHash 工具输入 Schema SHA-256
 * @param arguments 本次完整参数
 * @param invocationId 本次调用稳定幂等标识
 */
public record UnattendedToolInvocation(
        WorkspaceId workspaceId,
        String grantId,
        long grantRevision,
        String scheduleId,
        long scheduleRevision,
        ToolIdentity tool,
        long catalogRevision,
        String schemaHash,
        CanonicalPayload arguments,
        String invocationId) {
    /** 校验冻结身份和调用参数。 */
    public UnattendedToolInvocation {
        Objects.requireNonNull(workspaceId, "workspaceId");
        grantId = Preconditions.identifier(grantId, "grantId");
        grantRevision = Preconditions.positive(grantRevision, "grantRevision");
        scheduleId = Preconditions.identifier(scheduleId, "scheduleId");
        scheduleRevision = Preconditions.positive(scheduleRevision, "scheduleRevision");
        Objects.requireNonNull(tool, "tool");
        catalogRevision = Preconditions.positive(catalogRevision, "catalogRevision");
        schemaHash = Preconditions.digest(schemaHash, "schemaHash");
        Objects.requireNonNull(arguments, "arguments");
        invocationId = Preconditions.identifier(invocationId, "invocationId");
    }
}
