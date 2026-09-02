package com.javaclaw.api;

import java.time.Instant;
import java.util.Objects;

/**
 * Managed Worktree 导出的内容寻址 Patch 或 Backup。
 *
 * @param worktreeId Worktree
 * @param sourceRevision 生成内容时的 Worktree revision
 * @param kind Artifact 类型
 * @param attachment 内容寻址附件引用
 * @param baseCommit Patch 的冻结基线 commit
 * @param createdAt 生成并校验完成时间
 */
public record ManagedWorktreeArtifact(
        WorktreeId worktreeId,
        long sourceRevision,
        ManagedWorktreeArtifactKind kind,
        AttachmentRef attachment,
        String baseCommit,
        Instant createdAt) {
    /** 校验来源、附件和时间。 */
    public ManagedWorktreeArtifact {
        Objects.requireNonNull(worktreeId, "worktreeId");
        sourceRevision = Preconditions.positive(sourceRevision, "sourceRevision");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(attachment, "attachment");
        baseCommit = Preconditions.text(baseCommit, "baseCommit").toLowerCase(java.util.Locale.ROOT);
        if (!baseCommit.matches("(?:[0-9a-f]{40}|[0-9a-f]{64})")) {
            throw new IllegalArgumentException("baseCommit must be a full Git object id");
        }
        Objects.requireNonNull(createdAt, "createdAt");
    }
}
