package com.javaclaw.api;

import java.util.Objects;

/**
 * Schedule Occurrence 传递给自动化 Turn 的无人值守执行来源。
 *
 * <p>该来源只能由平台的 Schedule 投递链创建，并随执行快照和 Turn 原子持久化。它本身不授予任何权限，工具执行前仍需匹配精确的 {@link UnattendedToolGrant} 并重新检查实时权限。
 *
 * @param workspaceId Occurrence 所属 Workspace
 * @param scheduleId 冻结 Schedule 定义标识
 * @param scheduleRevision 冻结 Schedule revision
 * @param occurrenceId 独立 Occurrence 标识
 */
public record UnattendedExecutionScope(
        WorkspaceId workspaceId, String scheduleId, long scheduleRevision, String occurrenceId) {
    /** 校验 Workspace、Schedule 与 Occurrence 的稳定身份。 */
    public UnattendedExecutionScope {
        Objects.requireNonNull(workspaceId, "workspaceId");
        scheduleId = Preconditions.identifier(scheduleId, "scheduleId");
        scheduleRevision = Preconditions.positive(scheduleRevision, "scheduleRevision");
        occurrenceId = Preconditions.identifier(occurrenceId, "occurrenceId");
    }
}
