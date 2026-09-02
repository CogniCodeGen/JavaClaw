package com.javaclaw.server.persistence;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import com.javaclaw.api.ManagedWorktree;
import com.javaclaw.api.WorkspaceId;
import com.javaclaw.api.WorktreeId;

/** 受管 Worktree 根目录的创建、归属与临时路径校验。 */
final class ManagedWorktreePaths {
    private final Path root;

    ManagedWorktreePaths(Path dataRoot) {
        root = dataRoot.resolve("worktrees").toAbsolutePath().normalize();
        createRoot();
    }

    Path root() {
        return root;
    }

    Path destination(WorkspaceId workspaceId, WorktreeId id) {
        Path workspaceRoot = root.resolve(workspaceId.toString()).normalize();
        try {
            Files.createDirectories(workspaceRoot);
            Path real = workspaceRoot.toRealPath();
            if (!real.startsWith(root.toRealPath())) {
                throw new PersistenceException("Worktree 管理目录越界");
            }
            return real.resolve(id.toString()).normalize();
        } catch (IOException failure) {
            throw new PersistenceException("无法创建 Worktree 管理目录", failure);
        }
    }

    Path scratch(ManagedWorktree worktree) {
        requireManaged(worktree);
        return root.resolve("scratch").resolve(worktree.id().toString()).normalize();
    }

    Path cleanupIsolation(ManagedWorktree worktree) {
        requireManaged(worktree);
        Path cleanupRoot = root.resolve("cleanup").normalize();
        try {
            Files.createDirectories(cleanupRoot);
            Path real = cleanupRoot.toRealPath();
            if (!real.startsWith(root.toRealPath())) {
                throw new PersistenceException("Worktree cleanup 隔离目录越界");
            }
            return real.resolve(worktree.id().toString()).normalize();
        } catch (IOException failure) {
            throw new PersistenceException("无法创建 Worktree cleanup 隔离目录", failure);
        }
    }

    void requireCleanupIsolation(ManagedWorktree worktree, Path candidate) {
        Path expected = cleanupIsolation(worktree);
        if (!expected.equals(candidate.toAbsolutePath().normalize())) {
            throw new PersistenceException("Worktree cleanup 路径不属于平台隔离目录");
        }
    }

    void requireManaged(ManagedWorktree worktree) {
        Path expected = destination(worktree.workspaceId(), worktree.id());
        if (!expected.equals(worktree.executionRoot().toAbsolutePath().normalize())) {
            throw new PersistenceException("Worktree execution root 不属于平台管理目录");
        }
    }

    private void createRoot() {
        try {
            Files.createDirectories(root);
            if (Files.isSymbolicLink(root)) {
                throw new PersistenceException("Worktree 管理根目录不能是符号链接");
            }
        } catch (IOException failure) {
            throw new PersistenceException("无法创建 Worktree 管理根目录", failure);
        }
    }
}
