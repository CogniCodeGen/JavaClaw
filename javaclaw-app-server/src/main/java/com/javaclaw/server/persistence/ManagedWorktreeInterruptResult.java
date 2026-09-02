package com.javaclaw.server.persistence;

import java.util.Objects;
import java.util.Optional;

import com.javaclaw.api.ManagedWorktree;
import com.javaclaw.api.TurnId;

/**
 * Worktree 中断事务结果；RPC 返回 Worktree，Turn ID 只用于唤醒进程内取消。
 *
 * @param worktree 已中断快照
 * @param turnId 同事务写入取消请求的活动 Turn
 */
public record ManagedWorktreeInterruptResult(ManagedWorktree worktree, Optional<TurnId> turnId) {
    /** 校验并复制结果。 */
    public ManagedWorktreeInterruptResult {
        Objects.requireNonNull(worktree, "worktree");
        turnId = Objects.requireNonNull(turnId, "turnId");
    }
}
