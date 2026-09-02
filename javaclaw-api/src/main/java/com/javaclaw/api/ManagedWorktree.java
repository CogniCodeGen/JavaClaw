package com.javaclaw.api;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * 只由平台为写型子 Thread 创建的 Git Worktree 快照。
 *
 * @param id Worktree 标识
 * @param workspaceId 所属 Workspace
 * @param parentThreadId 唯一父 Thread
 * @param childThreadId 唯一写型子 Thread
 * @param executionRoot 服务端分配的规范绝对执行根
 * @param baseCommit 创建时冻结的 Git commit
 * @param state 生命周期状态
 * @param revision 乐观锁版本，从 1 开始
 * @param backup 最近一次可验证 Backup Attachment；未备份为空
 * @param createdAt 创建时间
 * @param updatedAt 最近状态变更时间
 */
public record ManagedWorktree(
        WorktreeId id,
        WorkspaceId workspaceId,
        ThreadId parentThreadId,
        ThreadId childThreadId,
        Path executionRoot,
        String baseCommit,
        ManagedWorktreeState state,
        long revision,
        Optional<AttachmentRef> backup,
        Instant createdAt,
        Instant updatedAt) {
    /** 校验父子归属、路径、commit、版本与时间。 */
    public ManagedWorktree {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(workspaceId, "workspaceId");
        Objects.requireNonNull(parentThreadId, "parentThreadId");
        Objects.requireNonNull(childThreadId, "childThreadId");
        if (parentThreadId.equals(childThreadId)) {
            throw new IllegalArgumentException("parent and child Thread must differ");
        }
        executionRoot = Objects.requireNonNull(executionRoot, "executionRoot")
                .toAbsolutePath()
                .normalize();
        baseCommit = Preconditions.text(baseCommit, "baseCommit").toLowerCase(Locale.ROOT);
        if (!baseCommit.matches("(?:[0-9a-f]{40}|[0-9a-f]{64})")) {
            throw new IllegalArgumentException("baseCommit must be a full Git object id");
        }
        Objects.requireNonNull(state, "state");
        revision = Preconditions.positive(revision, "revision");
        backup = Objects.requireNonNull(backup, "backup");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        if (updatedAt.isBefore(createdAt)) {
            throw new IllegalArgumentException("updatedAt must not be before createdAt");
        }
        if (state == ManagedWorktreeState.CLEANED && backup.isEmpty()) {
            throw new IllegalArgumentException("cleaned Worktree requires a verified backup");
        }
    }
}
