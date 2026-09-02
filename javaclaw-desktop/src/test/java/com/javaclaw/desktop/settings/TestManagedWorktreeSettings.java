package com.javaclaw.desktop.settings;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.javaclaw.api.AttachmentRef;
import com.javaclaw.api.ConversationThread;
import com.javaclaw.api.ManagedWorktree;
import com.javaclaw.api.ManagedWorktreeArtifact;
import com.javaclaw.api.ManagedWorktreeArtifactKind;
import com.javaclaw.api.ManagedWorktreeState;
import com.javaclaw.api.ThreadExecutionIntent;
import com.javaclaw.api.ThreadId;
import com.javaclaw.api.ThreadStatus;
import com.javaclaw.api.Workspace;
import com.javaclaw.api.WorkspaceId;
import com.javaclaw.api.WorktreeId;
import com.javaclaw.desktop.DesktopTestFixtures;
import com.javaclaw.protocol.WorktreeRpcContracts;

/** Worktree 恢复中心测试的纯内存状态与副作用。 */
final class TestManagedWorktreeSettings {
    private static final Instant NOW = Instant.parse("2026-09-01T01:00:00Z");
    private final Workspace workspace = DesktopTestFixtures.workspace();
    private final ConversationThread parent = DesktopTestFixtures.thread(workspace);
    private final ConversationThread child = childThread();
    private final List<ManagedWorktree> worktrees = new ArrayList<>(List.of(initialWorktree()));

    List<ManagedWorktree> list(WorkspaceId workspaceId, boolean includeCleaned) {
        return worktrees.stream()
                .filter(value -> value.workspaceId().equals(workspaceId))
                .filter(value -> includeCleaned || value.state() != ManagedWorktreeState.CLEANED)
                .toList();
    }

    ManagedWorktree interrupt(ManagedWorktree value) {
        return replace(value, ManagedWorktreeState.INTERRUPTED, value.backup());
    }

    ManagedWorktreeArtifact exportPatch(ManagedWorktree value) {
        return artifact(value, ManagedWorktreeArtifactKind.PATCH, "worktree.patch");
    }

    ManagedWorktreeArtifact backup(ManagedWorktree value) {
        ManagedWorktreeArtifact artifact = artifact(value, ManagedWorktreeArtifactKind.BACKUP, "worktree-backup.tar");
        replace(value, value.state(), Optional.of(artifact.attachment()));
        return artifact;
    }

    ManagedWorktree cleanup(ManagedWorktree value, String confirmation) {
        if (!WorktreeRpcContracts.CLEANUP_CONFIRMATION.equals(confirmation)) {
            throw new IllegalArgumentException("cleanup confirmation does not match");
        }
        ManagedWorktree current = current(value);
        if (current.backup().isEmpty()) {
            throw new IllegalStateException("cleanup 需要已验证 Backup");
        }
        return replace(current, ManagedWorktreeState.CLEANED, current.backup());
    }

    ConversationThread navigate(ThreadId threadId) {
        if (parent.id().equals(threadId)) {
            return parent;
        }
        if (child.id().equals(threadId)) {
            return child;
        }
        throw new IllegalArgumentException("Thread 不存在");
    }

    private ManagedWorktree replace(
            ManagedWorktree expected, ManagedWorktreeState state, Optional<AttachmentRef> backup) {
        ManagedWorktree current = current(expected);
        ManagedWorktree updated = new ManagedWorktree(
                current.id(),
                current.workspaceId(),
                current.parentThreadId(),
                current.childThreadId(),
                current.executionRoot(),
                current.baseCommit(),
                state,
                current.revision() + 1,
                backup,
                current.createdAt(),
                NOW.plusSeconds(current.revision()));
        worktrees.remove(current);
        worktrees.add(updated);
        return updated;
    }

    private ManagedWorktree current(ManagedWorktree expected) {
        return worktrees.stream()
                .filter(value -> value.id().equals(expected.id()))
                .findFirst()
                .orElseThrow();
    }

    private ManagedWorktreeArtifact artifact(ManagedWorktree value, ManagedWorktreeArtifactKind kind, String fileName) {
        return new ManagedWorktreeArtifact(
                value.id(),
                value.revision(),
                kind,
                new AttachmentRef("b".repeat(64), "application/octet-stream", fileName, 128),
                value.baseCommit(),
                NOW);
    }

    private ManagedWorktree initialWorktree() {
        return new ManagedWorktree(
                WorktreeId.parse("5ce503bd-f36b-4466-a29e-d5477fa31966"),
                workspace.id(),
                parent.id(),
                child.id(),
                Path.of("/tmp/javaclaw-worktree"),
                "1".repeat(40),
                ManagedWorktreeState.COMPLETED,
                1,
                Optional.empty(),
                NOW,
                NOW);
    }

    private ConversationThread childThread() {
        return new ConversationThread(
                ThreadId.parse("1ae28398-7b70-4919-bef0-d0daf7bd90bf"),
                workspace.id(),
                Optional.of(parent.id()),
                ThreadExecutionIntent.ISOLATED_WRITE,
                "隔离写任务",
                ThreadStatus.ACTIVE,
                1,
                NOW,
                NOW);
    }
}
