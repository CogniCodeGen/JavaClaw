package com.javaclaw.server.persistence;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.javaclaw.api.ManagedWorktree;
import com.javaclaw.api.ManagedWorktreeState;
import com.javaclaw.api.ThreadId;
import com.javaclaw.api.WorkspaceId;
import com.javaclaw.api.WorktreeId;

import static org.junit.jupiter.api.Assertions.assertThrows;

class ManagedWorktreePathsSecurityTest {
    private static final Instant NOW = Instant.parse("2026-09-01T00:00:00Z");

    @TempDir
    Path temporaryDirectory;

    @Test
    void 管理根Workspace目录和Cleanup目录不得通过符号链接越界() throws Exception {
        Path outside = Files.createDirectories(temporaryDirectory.resolve("outside"));
        Path linkedRootData = Files.createDirectories(temporaryDirectory.resolve("linked-root-data"));
        Files.createSymbolicLink(linkedRootData.resolve("worktrees"), outside);
        assertThrows(PersistenceException.class, () -> new ManagedWorktreePaths(linkedRootData));

        ManagedWorktreePaths workspacePaths =
                new ManagedWorktreePaths(temporaryDirectory.resolve("workspace-link-data"));
        WorkspaceId linkedWorkspace = WorkspaceId.random();
        Files.createSymbolicLink(workspacePaths.root().resolve(linkedWorkspace.toString()), outside);
        assertThrows(
                PersistenceException.class,
                () -> workspacePaths.destination(linkedWorkspace, new WorktreeId(UUID.randomUUID())));

        ManagedWorktreePaths cleanupPaths = new ManagedWorktreePaths(temporaryDirectory.resolve("cleanup-link-data"));
        ManagedWorktree worktree = managed(cleanupPaths, WorkspaceId.random(), new WorktreeId(UUID.randomUUID()));
        Files.createSymbolicLink(cleanupPaths.root().resolve("cleanup"), outside);
        assertThrows(PersistenceException.class, () -> cleanupPaths.cleanupIsolation(worktree));
    }

    @Test
    void 路径校验拒绝非受管执行根和非平台隔离目录() {
        ManagedWorktreePaths paths = new ManagedWorktreePaths(temporaryDirectory.resolve("path-validation"));
        WorkspaceId workspaceId = WorkspaceId.random();
        WorktreeId worktreeId = new WorktreeId(UUID.randomUUID());
        ManagedWorktree managed = managed(paths, workspaceId, worktreeId);
        ManagedWorktree outside = new ManagedWorktree(
                managed.id(),
                managed.workspaceId(),
                managed.parentThreadId(),
                managed.childThreadId(),
                temporaryDirectory.resolve("outside-execution"),
                managed.baseCommit(),
                managed.state(),
                managed.revision(),
                managed.backup(),
                managed.createdAt(),
                managed.updatedAt());

        assertThrows(PersistenceException.class, () -> paths.requireManaged(outside));
        assertThrows(
                PersistenceException.class,
                () -> paths.requireCleanupIsolation(managed, temporaryDirectory.resolve("outside-cleanup")));
    }

    private static ManagedWorktree managed(ManagedWorktreePaths paths, WorkspaceId workspaceId, WorktreeId id) {
        return new ManagedWorktree(
                id,
                workspaceId,
                ThreadId.random(),
                ThreadId.random(),
                paths.destination(workspaceId, id),
                "a".repeat(40),
                ManagedWorktreeState.READY,
                1,
                Optional.empty(),
                NOW,
                NOW);
    }
}
