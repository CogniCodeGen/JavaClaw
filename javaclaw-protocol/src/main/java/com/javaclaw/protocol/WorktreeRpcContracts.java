package com.javaclaw.protocol;

import java.util.List;
import java.util.Objects;

import com.javaclaw.api.ManagedWorktree;
import com.javaclaw.api.WorkspaceId;
import com.javaclaw.api.WorktreeId;

/** Managed Worktree 恢复中心的 Protocol v3 payload。 */
public final class WorktreeRpcContracts {
    /** cleanup 的固定危险确认文本。 */
    public static final String CLEANUP_CONFIRMATION = "CLEANUP WORKTREE";

    private WorktreeRpcContracts() {}

    /**
     * Workspace Worktree 列表查询。
     *
     * @param workspaceId Workspace
     * @param includeCleaned 是否包含已经清理的历史记录
     */
    public record ListPayload(WorkspaceId workspaceId, boolean includeCleaned) {
        /** 校验 Workspace。 */
        public ListPayload {
            Objects.requireNonNull(workspaceId, "workspaceId");
        }
    }

    /**
     * 单个 Worktree 查询。
     *
     * @param worktreeId Worktree
     */
    public record ReadPayload(WorktreeId worktreeId) {
        /** 校验标识。 */
        public ReadPayload {
            Objects.requireNonNull(worktreeId, "worktreeId");
        }
    }

    /**
     * 中断写型子 Thread。
     *
     * @param worktreeId Worktree
     * @param reason 脱敏原因，最多 500 字符
     */
    public record InterruptPayload(WorktreeId worktreeId, String reason) {
        /** 校验标识和原因。 */
        public InterruptPayload {
            Objects.requireNonNull(worktreeId, "worktreeId");
            reason = text(reason, "reason");
            if (reason.length() > 500) {
                throw new IllegalArgumentException("reason must not exceed 500 characters");
            }
        }
    }

    /**
     * Patch 或 Backup 命令参数。
     *
     * @param worktreeId Worktree
     */
    public record ArtifactPayload(WorktreeId worktreeId) {
        /** 校验标识。 */
        public ArtifactPayload {
            Objects.requireNonNull(worktreeId, "worktreeId");
        }
    }

    /**
     * cleanup 命令参数。
     *
     * @param worktreeId Worktree
     * @param confirmation 必须精确为 {@value #CLEANUP_CONFIRMATION}
     */
    public record CleanupPayload(WorktreeId worktreeId, String confirmation) {
        /** 校验标识和危险确认。 */
        public CleanupPayload {
            Objects.requireNonNull(worktreeId, "worktreeId");
            if (!CLEANUP_CONFIRMATION.equals(confirmation)) {
                throw new IllegalArgumentException("cleanup confirmation does not match");
            }
        }
    }

    /**
     * Worktree 列表结果。
     *
     * @param worktrees 按创建时间排序的快照
     */
    public record ListResult(List<ManagedWorktree> worktrees) {
        /** 复制结果。 */
        public ListResult {
            worktrees = List.copyOf(worktrees);
        }
    }

    private static String text(String value, String name) {
        String normalized = Objects.requireNonNull(value, name).strip();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return normalized;
    }
}
