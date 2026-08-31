package com.javaclaw.server.persistence;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.javaclaw.core.api.ThreadId;
import com.javaclaw.server.collaboration.WorktreeRepository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class H2CollaborationRepositoriesTest {
    @TempDir
    Path temporary;

    @Test
    void cleanupIntentKeepsBackupAcrossRestartAndBindsRevisionAndConfirmation() throws Exception {
        Path root = Files.createDirectory(temporary.resolve("recovery"));
        Path data = temporary.resolve("recovery-data");
        ThreadId child;
        var backup = "a".repeat(64);
        try (H2Persistence store = new H2Persistence(data)) {
            var workspace = store.workspaces().create("test", root, "workspace");
            var parent = store.journal().createThread(workspace.id().value(), root, "parent", null, null);
            child = store.journal()
                    .createThread(workspace.id().value(), root, "child", parent.id(), null)
                    .id();
            var worktrees = new H2WorktreeRepository(store.database());
            worktrees.create(new WorktreeRepository.WorktreeDraft(
                    "worktree_recovery", workspace.id().value(), parent.id(), child, root, "1".repeat(40)));
            var request = new com.javaclaw.server.collaboration.WorktreeRecoveryUseCases.CleanupRequest(
                    child, 1, true, "cleanup");
            var wrong = new com.javaclaw.server.collaboration.WorktreeRecoveryUseCases.CleanupRequest(
                    child, 1, false, "unapproved");
            assertThrows(IllegalStateException.class, () -> worktrees.beginCleanup(wrong, backup));
            assertEquals(worktrees.beginCleanup(request, backup), worktrees.beginCleanup(request, backup));
            assertTrue(worktrees.replayCleanup(request).isEmpty());
        }
        try (H2Persistence store = new H2Persistence(data)) {
            var worktrees = new H2WorktreeRepository(store.database());
            var request = new com.javaclaw.server.collaboration.WorktreeRecoveryUseCases.CleanupRequest(
                    child, 1, true, "cleanup");
            assertTrue(worktrees.pendingCleanup(request).orElseThrow().details().contains(backup));
            var completed = worktrees.finishCleanup(request, true);
            assertEquals(WorktreeRepository.State.CLEANED, completed.state());
            assertEquals(2, completed.revision());
            assertEquals(completed, worktrees.replayCleanup(request).orElseThrow());
            assertEquals(completed, worktrees.finishCleanup(request, false));
            var changed = new com.javaclaw.server.collaboration.WorktreeRecoveryUseCases.CleanupRequest(
                    child, 2, true, "cleanup");
            assertThrows(IllegalStateException.class, () -> worktrees.replayCleanup(changed));
        }
    }

    @Test
    void spawnIdempotencyAndWorktreeLifecycleAreDurable() throws Exception {
        Path workspaceRoot = Files.createDirectories(temporary.resolve("workspace"));
        Path worktreeRoot = Files.createDirectories(temporary.resolve("managed-worktree"));
        try (H2Persistence store = new H2Persistence(temporary.resolve("data"))) {
            var workspace = store.workspaces().create("test", workspaceRoot, "workspace-1");
            var parent = store.journal().createThread(workspace.id().value(), workspaceRoot, "parent", null, null);
            var child = store.journal().createThread(workspace.id().value(), worktreeRoot, "child", parent.id(), null);
            H2CollaborationRepository collaborations = new H2CollaborationRepository(store.database());
            H2WorktreeRepository worktrees = new H2WorktreeRepository(store.database());

            var first = collaborations.recordSpawn(
                    parent.id(), child.id(), true, "profile_subagent", "inspect", "spawn-key");
            var replay = collaborations.recordSpawn(
                    parent.id(), child.id(), true, "profile_subagent", "inspect", "spawn-key");
            assertEquals(first, replay);
            assertThrows(
                    IllegalStateException.class,
                    () -> collaborations.recordSpawn(
                            parent.id(), ThreadId.random(), false, "profile_subagent", "different", "spawn-key"));

            var active = worktrees.create(new WorktreeRepository.WorktreeDraft(
                    "worktree_1", workspace.id().value(), parent.id(), child.id(), worktreeRoot, "1".repeat(40)));
            assertEquals(WorktreeRepository.State.ACTIVE, active.state());
            assertTrue(active.cleanupRequired());
            var merged = worktrees.setState(
                    active.id(), WorktreeRepository.State.MERGED, true, "patch=sha256", active.revision());
            assertEquals(active.revision() + 1, merged.revision());
            assertEquals(1, worktrees.listRequiringCleanup().size());
            var cleaned = worktrees.setState(
                    merged.id(), WorktreeRepository.State.CLEANED, false, merged.details(), merged.revision());
            assertEquals(WorktreeRepository.State.CLEANED, cleaned.state());
            assertTrue(worktrees.listRequiringCleanup().isEmpty());
        }
    }
}
