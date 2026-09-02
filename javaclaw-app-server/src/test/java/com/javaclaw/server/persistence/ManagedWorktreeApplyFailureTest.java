package com.javaclaw.server.persistence;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import com.javaclaw.api.AgentTurn;
import com.javaclaw.api.AttachmentMetadata;
import com.javaclaw.api.AttachmentScope;
import com.javaclaw.api.ConversationThread;
import com.javaclaw.api.ManagedWorktree;
import com.javaclaw.api.ManagedWorktreeArtifact;
import com.javaclaw.api.ManagedWorktreeState;
import com.javaclaw.api.ThreadExecutionIntent;
import com.javaclaw.api.TurnId;
import com.javaclaw.api.Workspace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ManagedWorktreeApplyFailureTest extends ManagedWorktreeFailureTestSupport {
    @Test
    void applyRejectsMalformedMissingAndWrongMediaTypeAttachmentsBeforeIntent() throws Exception {
        WorktreeFixture fixture = provision("attachment-validation");
        changeTrackedFile(fixture.worktree(), "attachment\n");
        byte[] content = "not a patch".getBytes(StandardCharsets.UTF_8);
        AttachmentMetadata wrongMedia = attachments.store(
                AttachmentScope.workspace(workspace.id()),
                identity("attachment/internal/store", "wrong-media", Map.of("content", "wrong")),
                "text/plain",
                content);

        assertThrows(
                PersistenceException.class,
                () -> worktrees.apply(
                        "bad-digest", parentTurn.id(), fixture.worktree().id(), "invalid"));
        assertThrows(
                PersistenceException.class,
                () -> worktrees.apply(
                        "missing-digest", parentTurn.id(), fixture.worktree().id(), "0".repeat(64)));
        assertThrows(
                PersistenceException.class,
                () -> worktrees.apply(
                        "wrong-media", parentTurn.id(), fixture.worktree().id(), wrongMedia.digest()));
        assertEquals(
                ManagedWorktreeState.READY,
                worktrees.read(fixture.worktree().id()).state());
    }

    @Test
    void applyRequiresTheExactActiveParentTurn() throws Exception {
        WorktreeFixture fixture = provision("authority");
        changeTrackedFile(fixture.worktree(), "authority\n");
        ManagedWorktreeArtifact patch = exportPatch(fixture, "authority");

        assertThrows(
                PersistenceException.class,
                () -> worktrees.apply(
                        "wrong-authority",
                        TurnId.random(),
                        fixture.worktree().id(),
                        patch.attachment().digest()));

        assertEquals(
                ManagedWorktreeState.READY,
                worktrees.read(fixture.worktree().id()).state());
        assertEquals("base\n", Files.readString(repository.resolve("tracked.txt")));
    }

    @Test
    void applyRejectsPatchWhenSourceChangedBeforeIntent() throws Exception {
        WorktreeFixture fixture = provision("source-before-intent");
        changeTrackedFile(fixture.worktree(), "first\n");
        ManagedWorktreeArtifact patch = exportPatch(fixture, "source-before-intent");
        changeTrackedFile(fixture.worktree(), "second\n");

        assertThrows(
                PersistenceException.class,
                () -> worktrees.apply(
                        "source-before-intent",
                        parentTurn.id(),
                        fixture.worktree().id(),
                        patch.attachment().digest()));

        assertEquals(
                ManagedWorktreeState.READY,
                worktrees.read(fixture.worktree().id()).state());
        assertEquals("base\n", Files.readString(repository.resolve("tracked.txt")));
    }

    @Test
    void applyMarksFailedWhenSourceChangesAfterIntent() throws Exception {
        WorktreeFixture fixture = provision("source-after-intent");
        changeTrackedFile(fixture.worktree(), "first\n");
        ManagedWorktreeArtifact patch = exportPatch(fixture, "source-after-intent");
        AtomicInteger patchCalls = new AtomicInteger();
        sandbox.before(command -> {
            if (command.id().equals("worktree-patch") && patchCalls.incrementAndGet() == 2) {
                changeTrackedFile(fixture.worktree(), "second\n");
            }
        });

        assertThrows(
                PersistenceException.class,
                () -> worktrees.apply(
                        "source-after-intent",
                        parentTurn.id(),
                        fixture.worktree().id(),
                        patch.attachment().digest()));

        assertEquals(
                ManagedWorktreeState.FAILED,
                worktrees.read(fixture.worktree().id()).state());
        assertEquals("base\n", Files.readString(repository.resolve("tracked.txt")));
    }

    @Test
    void readOnlyParentFailsClosedWithoutWritingWorkspace() throws Exception {
        ConversationThread readOnly = createChildThread(parent, ThreadExecutionIntent.READ_ONLY, "read-only-parent");
        AgentTurn readOnlyTurn = startTurn(readOnly, repository, "read-only-parent");
        WorktreeFixture fixture = provision(readOnly, "read-only-child");
        changeTrackedFile(fixture.worktree(), "read-only\n");
        ManagedWorktreeArtifact patch = exportPatch(fixture, "read-only");

        assertThrows(
                PersistenceException.class,
                () -> worktrees.apply(
                        "read-only-parent",
                        readOnlyTurn.id(),
                        fixture.worktree().id(),
                        patch.attachment().digest()));

        assertEquals(
                ManagedWorktreeState.UNKNOWN_OUTCOME,
                worktrees.read(fixture.worktree().id()).state());
        assertEquals("base\n", Files.readString(repository.resolve("tracked.txt")));
    }

    @Test
    void mismatchedParentWorkspaceFailsClosedWithoutWritingEitherCheckout() throws Exception {
        WorktreeFixture fixture = provision("wrong-workspace");
        changeTrackedFile(fixture.worktree(), "wrong-workspace\n");
        ManagedWorktreeArtifact patch = exportPatch(fixture, "wrong-workspace");
        Path foreignRoot = initializeRepository(temporaryDirectory.resolve("foreign-repository"));
        Workspace foreignWorkspace = createWorkspace("foreign", foreignRoot);
        ConversationThread foreignParent = createRootThread(foreignWorkspace, "foreign");
        AgentTurn foreignTurn = startTurn(foreignParent, foreignRoot, "foreign");
        replaceWorktreeParent(fixture.worktree(), foreignParent.id());

        assertThrows(
                IllegalStateException.class,
                () -> worktrees.apply(
                        "wrong-workspace",
                        foreignTurn.id(),
                        fixture.worktree().id(),
                        patch.attachment().digest()));

        assertEquals(
                ManagedWorktreeState.UNKNOWN_OUTCOME,
                worktrees.read(fixture.worktree().id()).state());
        assertEquals("base\n", Files.readString(repository.resolve("tracked.txt")));
        assertEquals("base\n", Files.readString(foreignRoot.resolve("tracked.txt")));
    }

    @Test
    void gitWriteFailureMarksUnknownOutcomeAndPreventsAutomaticRetry() throws Exception {
        WorktreeFixture fixture = provision("git-write-failure");
        changeTrackedFile(fixture.worktree(), "write-failure\n");
        ManagedWorktreeArtifact patch = exportPatch(fixture, "git-write-failure");
        sandbox.reject("worktree-apply");

        assertThrows(
                PersistenceException.class,
                () -> worktrees.apply(
                        "git-write-failure",
                        parentTurn.id(),
                        fixture.worktree().id(),
                        patch.attachment().digest()));

        assertEquals(
                ManagedWorktreeState.UNKNOWN_OUTCOME,
                worktrees.read(fixture.worktree().id()).state());
        assertEquals("base\n", Files.readString(repository.resolve("tracked.txt")));
        sandbox.clearControls();
        assertThrows(
                PersistenceException.class,
                () -> worktrees.apply(
                        "git-write-failure",
                        parentTurn.id(),
                        fixture.worktree().id(),
                        patch.attachment().digest()));
    }

    @Test
    void revisionDriftAfterGitWriteKeepsTheSideEffectButMarksUnknownOutcome() throws Exception {
        WorktreeFixture fixture = provision("finish-drift");
        changeTrackedFile(fixture.worktree(), "applied-before-drift\n");
        ManagedWorktreeArtifact patch = exportPatch(fixture, "finish-drift");
        sandbox.after(command -> {
            if (command.id().equals("worktree-apply")) {
                advanceWorktreeRevision(fixture.worktree());
            }
        });

        assertThrows(
                PersistenceException.class,
                () -> worktrees.apply(
                        "finish-drift",
                        parentTurn.id(),
                        fixture.worktree().id(),
                        patch.attachment().digest()));

        ManagedWorktree unknown = worktrees.read(fixture.worktree().id());
        assertEquals(ManagedWorktreeState.UNKNOWN_OUTCOME, unknown.state());
        assertEquals("applied-before-drift\n", Files.readString(repository.resolve("tracked.txt")));
    }

    @Test
    void successfulApplyIsRecoverableAndRejectsIdempotencyKeyReuse() throws Exception {
        WorktreeFixture fixture = provision("apply-recovery");
        changeTrackedFile(fixture.worktree(), "applied\n");
        ManagedWorktreeArtifact patch = exportPatch(fixture, "apply-recovery");

        ManagedWorktree applied = worktrees.apply(
                "apply-recovery",
                parentTurn.id(),
                fixture.worktree().id(),
                patch.attachment().digest());

        assertEquals(ManagedWorktreeState.APPLIED, applied.state());
        assertEquals(
                applied,
                worktrees.apply(
                        "apply-recovery",
                        parentTurn.id(),
                        fixture.worktree().id(),
                        patch.attachment().digest()));
        assertThrows(
                PersistenceException.class,
                () -> worktrees.apply(
                        "apply-recovery", parentTurn.id(), fixture.worktree().id(), "f".repeat(64)));
        assertTrue(Files.exists(fixture.worktree().executionRoot()));
    }
}
