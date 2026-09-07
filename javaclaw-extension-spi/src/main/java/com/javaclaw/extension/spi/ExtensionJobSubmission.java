package com.javaclaw.extension.spi;

import java.util.Objects;

import com.javaclaw.api.CanonicalPayload;
import com.javaclaw.api.WorkspaceId;

/**
 * 创建后台扩展执行时冻结的定义快照。
 *
 * @param extensionId 所有者扩展
 * @param workspaceId 所属 Workspace
 * @param jobType 扩展内稳定的执行类型
 * @param definitionId 用户定义标识
 * @param definitionRevision 冻结的定义 revision
 * @param frozenInput 冻结 Role、模型、权限、预算和领域输入的规范快照
 * @param initialCheckpoint 首个工作单元之前的 checkpoint
 */
public record ExtensionJobSubmission(
        ExtensionId extensionId,
        WorkspaceId workspaceId,
        String jobType,
        String definitionId,
        long definitionRevision,
        CanonicalPayload frozenInput,
        CanonicalPayload initialCheckpoint) {
    /** 校验冻结定义。 */
    public ExtensionJobSubmission {
        Objects.requireNonNull(extensionId, "extensionId");
        Objects.requireNonNull(workspaceId, "workspaceId");
        jobType = identifier(jobType, "jobType");
        definitionId = identifier(definitionId, "definitionId");
        if (definitionRevision < 1) {
            throw new IllegalArgumentException("definitionRevision must be positive");
        }
        Objects.requireNonNull(frozenInput, "frozenInput");
        Objects.requireNonNull(initialCheckpoint, "initialCheckpoint");
    }

    private static String identifier(String value, String name) {
        String normalized = Objects.requireNonNull(value, name).strip();
        if (!normalized.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,239}")) {
            throw new IllegalArgumentException(name + " contains unsupported characters");
        }
        return normalized;
    }
}
