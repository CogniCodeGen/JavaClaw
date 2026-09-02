package com.javaclaw.extension.spi;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

import com.javaclaw.api.ExecutionState;
import com.javaclaw.api.WorkspaceId;

/**
 * 可安全返回给 SDK 与 Desktop 的 Extension Execution 摘要。
 *
 * <p>该契约刻意不包含 frozen input、checkpoint、工作单元 intent/result 或用户输入正文；这些恢复材料只属于 App Server。
 *
 * @param id Job 标识
 * @param extensionId 所有者扩展
 * @param workspaceId 所属 Workspace
 * @param jobType 扩展内稳定类型
 * @param definitionId 冻结 Definition 标识
 * @param definitionRevision 冻结 Definition revision
 * @param state 当前执行状态
 * @param revision Job 乐观锁版本
 * @param errorCode 脱敏稳定错误码；仅失败状态存在
 * @param createdAt 创建时间
 * @param updatedAt 最近更新时间
 */
public record ExtensionExecutionReceipt(
        String id,
        ExtensionId extensionId,
        WorkspaceId workspaceId,
        String jobType,
        String definitionId,
        long definitionRevision,
        ExecutionState state,
        long revision,
        Optional<String> errorCode,
        Instant createdAt,
        Instant updatedAt) {
    /** 校验公开摘要。 */
    public ExtensionExecutionReceipt {
        id = text(id, "id");
        Objects.requireNonNull(extensionId, "extensionId");
        Objects.requireNonNull(workspaceId, "workspaceId");
        jobType = text(jobType, "jobType");
        definitionId = text(definitionId, "definitionId");
        if (definitionRevision < 1 || revision < 1) {
            throw new IllegalArgumentException("execution revisions must be positive");
        }
        Objects.requireNonNull(state, "state");
        errorCode = Objects.requireNonNull(errorCode, "errorCode").map(value -> text(value, "errorCode"));
        if ((state == ExecutionState.FAILED) != errorCode.isPresent()) {
            throw new IllegalArgumentException("only failed execution requires an errorCode");
        }
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        if (updatedAt.isBefore(createdAt)) {
            throw new IllegalArgumentException("updatedAt must not be before createdAt");
        }
    }

    /**
     * 从仅供服务端恢复的完整 Job 创建脱敏摘要。
     *
     * @param job 完整持久 Job
     * @return 不含恢复 payload 的公开摘要
     */
    public static ExtensionExecutionReceipt from(ExtensionJob job) {
        ExtensionJob value = Objects.requireNonNull(job, "job");
        return new ExtensionExecutionReceipt(
                value.id(),
                value.extensionId(),
                value.workspaceId(),
                value.jobType(),
                value.definitionId(),
                value.definitionRevision(),
                value.state(),
                value.revision(),
                value.errorCode(),
                value.createdAt(),
                value.updatedAt());
    }

    private static String text(String value, String name) {
        String normalized = Objects.requireNonNull(value, name).strip();
        if (normalized.isEmpty() || normalized.length() > 500) {
            throw new IllegalArgumentException(name + " length is invalid");
        }
        return normalized;
    }
}
