package com.javaclaw.server.persistence;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.javaclaw.api.AttachmentRef;
import com.javaclaw.api.ManagedWorktree;
import com.javaclaw.api.ManagedWorktreeState;
import com.javaclaw.api.ThreadId;
import com.javaclaw.api.WorkspaceId;
import com.javaclaw.api.WorktreeId;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ManagedWorktreePolicyTest {
    @Test
    void artifactCleanupExecutionAndApplyStatesAreExplicitlyPartitioned() {
        EnumSet<ManagedWorktreeState> capturable = EnumSet.of(
                ManagedWorktreeState.READY,
                ManagedWorktreeState.INTERRUPTED,
                ManagedWorktreeState.COMPLETED,
                ManagedWorktreeState.FAILED,
                ManagedWorktreeState.APPLIED);
        EnumSet<ManagedWorktreeState> executable = EnumSet.of(
                ManagedWorktreeState.READY,
                ManagedWorktreeState.RUNNING,
                ManagedWorktreeState.INTERRUPTED,
                ManagedWorktreeState.COMPLETED,
                ManagedWorktreeState.FAILED);
        EnumSet<ManagedWorktreeState> startable = EnumSet.of(
                ManagedWorktreeState.READY,
                ManagedWorktreeState.INTERRUPTED,
                ManagedWorktreeState.COMPLETED,
                ManagedWorktreeState.FAILED);
        EnumSet<ManagedWorktreeState> applicable = EnumSet.of(
                ManagedWorktreeState.READY,
                ManagedWorktreeState.INTERRUPTED,
                ManagedWorktreeState.COMPLETED,
                ManagedWorktreeState.CONFLICTED,
                ManagedWorktreeState.FAILED);

        for (ManagedWorktreeState state : ManagedWorktreeState.values()) {
            ManagedWorktree worktree = worktree(state);
            assertPolicy(capturable.contains(state), () -> ManagedWorktreePolicy.requireCapturable(worktree));
            assertPolicy(capturable.contains(state), () -> ManagedWorktreePolicy.requireCleanupState(worktree));
            assertPolicy(executable.contains(state), () -> ManagedWorktreePolicy.requireExecutable(worktree));
            assertEquals(startable.contains(state), ManagedWorktreePolicy.mayStartExecution(worktree));
            assertPolicy(applicable.contains(state), () -> ManagedWorktreePolicy.requireApplicable(worktree));
        }
    }

    @Test
    void revisionsProvisionIdentityAndReasonsFailClosed() {
        ManagedWorktree ready = worktree(ManagedWorktreeState.READY);
        CommandIdentity valid = new CommandIdentity("worktree/provision", "valid", 0, "a".repeat(64));
        assertSame(valid, ManagedWorktreePolicy.requireCreate(valid, "worktree/provision"));
        assertDoesNotThrow(() -> ManagedWorktreePolicy.requireRevision(
                new CommandIdentity("worktree/update", "revision", ready.revision(), "b".repeat(64)), ready));
        assertThrows(
                PersistenceException.class,
                () -> ManagedWorktreePolicy.requireRevision(
                        new CommandIdentity("worktree/update", "stale", ready.revision() + 1, "c".repeat(64)), ready));
        assertThrows(
                PersistenceException.class,
                () -> ManagedWorktreePolicy.requireCreate(
                        new CommandIdentity("wrong", "wrong-method", 0, "d".repeat(64)), "worktree/provision"));
        assertThrows(
                PersistenceException.class,
                () -> ManagedWorktreePolicy.requireCreate(
                        new CommandIdentity("worktree/provision", "wrong-revision", 1, "e".repeat(64)),
                        "worktree/provision"));

        assertEquals("说明", ManagedWorktreePolicy.requireReason("  说明  "));
        assertThrows(PersistenceException.class, () -> ManagedWorktreePolicy.requireReason("  "));
        assertThrows(PersistenceException.class, () -> ManagedWorktreePolicy.requireReason("x".repeat(501)));
    }

    @Test
    void deterministicIdentifiersDigestsAndLocksAreStablePerResource() {
        ThreadId first = ThreadId.random();
        ThreadId second = ThreadId.random();
        WorktreeId firstId = ManagedWorktreePolicy.deterministicId(first);

        assertEquals(firstId, ManagedWorktreePolicy.deterministicId(first));
        assertFalse(firstId.equals(ManagedWorktreePolicy.deterministicId(second)));
        assertEquals(
                "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                ManagedWorktreePolicy.sha256("abc".getBytes(StandardCharsets.UTF_8)));
        assertSame(ManagedWorktreePolicy.resourceLock(firstId), ManagedWorktreePolicy.resourceLock(firstId));
        assertNotSame(
                ManagedWorktreePolicy.resourceLock(firstId),
                ManagedWorktreePolicy.resourceLock(ManagedWorktreePolicy.deterministicId(second)));
        WorkspaceId workspace = WorkspaceId.random();
        assertSame(ManagedWorktreePolicy.workspaceLock(workspace), ManagedWorktreePolicy.workspaceLock(workspace));
    }

    private static void assertPolicy(boolean allowed, Runnable operation) {
        if (allowed) {
            assertDoesNotThrow(operation::run);
        } else {
            assertThrows(PersistenceException.class, operation::run);
        }
    }

    private static ManagedWorktree worktree(ManagedWorktreeState state) {
        Optional<AttachmentRef> backup = state == ManagedWorktreeState.CLEANED
                ? Optional.of(new AttachmentRef("a".repeat(64), "application/octet-stream", "backup.patch", 1))
                : Optional.empty();
        Instant now = Instant.parse("2026-09-01T00:00:00Z");
        return new ManagedWorktree(
                new WorktreeId(UUID.randomUUID()),
                WorkspaceId.random(),
                ThreadId.random(),
                ThreadId.random(),
                Path.of("managed-worktree"),
                "b".repeat(40),
                state,
                1,
                backup,
                now,
                now);
    }
}
