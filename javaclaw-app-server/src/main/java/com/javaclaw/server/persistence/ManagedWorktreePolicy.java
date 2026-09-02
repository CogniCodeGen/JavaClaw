package com.javaclaw.server.persistence;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;

import com.javaclaw.api.ManagedWorktree;
import com.javaclaw.api.ThreadId;
import com.javaclaw.api.WorktreeId;

/** Managed Worktree 状态转换、revision 与输入不变量。 */
final class ManagedWorktreePolicy {
    private ManagedWorktreePolicy() {}

    static void requireCapturable(ManagedWorktree current) {
        switch (current.state()) {
            case READY, INTERRUPTED, COMPLETED, FAILED, APPLIED -> {
                return;
            }
            case RUNNING, CONFLICTED, APPLYING, UNKNOWN_OUTCOME, CLEANING, CLEANED ->
                throw PersistenceException.invalidRequest("当前 Worktree 状态不能生成稳定 Artifact: " + current.state());
        }
    }

    static void requireCleanupState(ManagedWorktree current) {
        switch (current.state()) {
            case READY, INTERRUPTED, COMPLETED, FAILED, APPLIED -> {
                return;
            }
            case RUNNING, CONFLICTED, APPLYING, UNKNOWN_OUTCOME, CLEANING, CLEANED ->
                throw PersistenceException.invalidRequest("当前 Worktree 状态禁止 cleanup: " + current.state());
        }
    }

    static void requireExecutable(ManagedWorktree current) {
        switch (current.state()) {
            case READY, RUNNING, INTERRUPTED, COMPLETED, FAILED -> {
                return;
            }
            case CONFLICTED, APPLYING, APPLIED, UNKNOWN_OUTCOME, CLEANING, CLEANED ->
                throw PersistenceException.invalidRequest("当前 Worktree 状态不能启动 Turn: " + current.state());
        }
    }

    static boolean mayStartExecution(ManagedWorktree current) {
        return switch (current.state()) {
            case READY, INTERRUPTED, COMPLETED, FAILED -> true;
            case RUNNING, CONFLICTED, APPLYING, APPLIED, UNKNOWN_OUTCOME, CLEANING, CLEANED -> false;
        };
    }

    static void requireApplicable(ManagedWorktree current) {
        switch (current.state()) {
            case READY, INTERRUPTED, COMPLETED, CONFLICTED, FAILED -> {
                return;
            }
            case RUNNING, APPLYING, APPLIED, UNKNOWN_OUTCOME, CLEANING, CLEANED ->
                throw PersistenceException.invalidRequest("当前 Worktree 状态不能应用 Patch: " + current.state());
        }
    }

    static void requireRevision(CommandIdentity identity, ManagedWorktree current) {
        if (identity.expectedRevision() != current.revision()) {
            throw PersistenceException.revisionConflict("Managed Worktree revision 已改变");
        }
    }

    static CommandIdentity requireCreate(CommandIdentity identity, String method) {
        CommandIdentity checked = Objects.requireNonNull(identity, "identity");
        if (!method.equals(checked.method()) || checked.expectedRevision() != 0) {
            throw PersistenceException.invalidRequest("Managed Worktree provision 命令不合法");
        }
        return checked;
    }

    static String requireReason(String reason) {
        String normalized = Objects.requireNonNull(reason, "reason").strip();
        if (normalized.isEmpty() || normalized.length() > 500) {
            throw PersistenceException.invalidRequest("中断原因长度不合法");
        }
        return normalized;
    }

    static WorktreeId deterministicId(ThreadId childThreadId) {
        byte[] name = ("javaclaw-managed-worktree:" + childThreadId).getBytes(StandardCharsets.UTF_8);
        return new WorktreeId(UUID.nameUUIDFromBytes(name));
    }

    static Object resourceLock(WorktreeId id) {
        return CommandLocks.forKey("managed-worktree:" + Objects.requireNonNull(id, "worktreeId"));
    }

    static Object workspaceLock(com.javaclaw.api.WorkspaceId id) {
        return CommandLocks.forKey("managed-workspace:" + Objects.requireNonNull(id, "workspaceId"));
    }

    static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 is unavailable", failure);
        }
    }
}
