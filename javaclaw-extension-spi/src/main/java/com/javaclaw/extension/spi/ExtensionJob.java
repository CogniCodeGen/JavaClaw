package com.javaclaw.extension.spi;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

import com.javaclaw.api.CanonicalPayload;
import com.javaclaw.api.ExecutionState;
import com.javaclaw.api.WorkspaceId;

/**
 * 平台托管的可恢复 Extension Job 快照。
 *
 * @param id Job 标识
 * @param extensionId 所有者扩展
 * @param workspaceId 所属 Workspace
 * @param jobType 扩展内稳定类型
 * @param definitionId 冻结定义标识
 * @param definitionRevision 冻结定义 revision
 * @param frozenInput 不随定义更新而变化的输入快照
 * @param state 当前执行状态
 * @param revision Job 乐观锁版本
 * @param checkpoint 最近完成工作单元提交的 checkpoint
 * @param nextUnitSequence 下一个工作单元序号，从 1 开始
 * @param activeUnitSequence 已记录意图但尚未提交结果的工作单元序号
 * @param errorCode 脱敏稳定错误码；只有失败状态可以包含
 * @param createdAt 创建时间
 * @param updatedAt 最近提交时间
 */
public record ExtensionJob(
        String id,
        ExtensionId extensionId,
        WorkspaceId workspaceId,
        String jobType,
        String definitionId,
        long definitionRevision,
        CanonicalPayload frozenInput,
        ExecutionState state,
        long revision,
        CanonicalPayload checkpoint,
        long nextUnitSequence,
        Optional<Long> activeUnitSequence,
        Optional<String> errorCode,
        Instant createdAt,
        Instant updatedAt) {
    /** 校验 Job 状态与恢复指针。 */
    public ExtensionJob {
        id = identifier(id, "id");
        Objects.requireNonNull(extensionId, "extensionId");
        Objects.requireNonNull(workspaceId, "workspaceId");
        jobType = identifier(jobType, "jobType");
        definitionId = identifier(definitionId, "definitionId");
        if (definitionRevision < 1 || revision < 1 || nextUnitSequence < 1) {
            throw new IllegalArgumentException("job revisions and nextUnitSequence must be positive");
        }
        Objects.requireNonNull(frozenInput, "frozenInput");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(checkpoint, "checkpoint");
        activeUnitSequence = Objects.requireNonNull(activeUnitSequence, "activeUnitSequence");
        if (activeUnitSequence.isPresent()) {
            long sequence = activeUnitSequence.orElseThrow();
            if (sequence < 1 || sequence >= nextUnitSequence || state != ExecutionState.RUNNING) {
                throw new IllegalArgumentException("active unit must be a recorded RUNNING unit");
            }
        }
        errorCode = Objects.requireNonNull(errorCode, "errorCode").map(value -> identifier(value, "errorCode"));
        if ((state == ExecutionState.FAILED) != errorCode.isPresent()) {
            throw new IllegalArgumentException("only failed job requires an errorCode");
        }
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        if (updatedAt.isBefore(createdAt)) {
            throw new IllegalArgumentException("updatedAt must not be before createdAt");
        }
    }

    private static String identifier(String value, String name) {
        String normalized = Objects.requireNonNull(value, name).strip();
        if (!normalized.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,239}")) {
            throw new IllegalArgumentException(name + " contains unsupported characters");
        }
        return normalized;
    }
}
