package com.javaclaw.server.persistence;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import com.javaclaw.api.ManagedWorktree;
import com.javaclaw.api.ManagedWorktreeArtifact;
import com.javaclaw.api.ManagedWorktreeState;
import com.javaclaw.api.WorktreeId;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ManagedWorktreeArtifactFailureTest extends ManagedWorktreeFailureTestSupport {
    @Test
    void backupRejectsSourceChangeDuringStableReadback() throws Exception {
        WorktreeFixture fixture = provision("backup-drift");
        changeTrackedFile(fixture.worktree(), "first\n");
        AtomicInteger patchCalls = new AtomicInteger();
        sandbox.before(command -> {
            if (command.id().equals("worktree-patch") && patchCalls.incrementAndGet() == 2) {
                changeTrackedFile(fixture.worktree(), "second\n");
            }
        });

        assertThrows(PersistenceException.class, () -> backup(fixture, "backup-drift"));

        ManagedWorktree current = worktrees.read(fixture.worktree().id());
        assertEquals(ManagedWorktreeState.READY, current.state());
        assertTrue(current.backup().isEmpty());
        assertEquals("second\n", Files.readString(current.executionRoot().resolve("tracked.txt")));
    }

    @Test
    void cleanupVerificationFailureRestoresExecutionRootAndMarksFailed() throws Exception {
        WorktreeFixture fixture = provision("cleanup-restore");
        changeTrackedFile(fixture.worktree(), "backed-up\n");
        backup(fixture, "cleanup-restore");
        ManagedWorktree ready = worktrees.read(fixture.worktree().id());
        changeTrackedFile(ready, "drifted\n");
        Path isolation = new ManagedWorktreePaths(database.dataRoot()).cleanupIsolation(ready);

        assertThrows(
                PersistenceException.class,
                () -> worktrees.cleanup(identity("worktree/cleanup", "cleanup-restore", ready), ready.id()));

        ManagedWorktree failed = worktrees.read(ready.id());
        assertEquals(ManagedWorktreeState.FAILED, failed.state());
        assertTrue(Files.isDirectory(failed.executionRoot()));
        assertFalse(Files.exists(isolation));
        assertEquals("drifted\n", Files.readString(failed.executionRoot().resolve("tracked.txt")));
    }

    @Test
    void cleanupResumesFromDurableIntentAfterProcessStopsPostIsolation() throws Exception {
        WorktreeFixture fixture = provision("cleanup-recovery");
        changeTrackedFile(fixture.worktree(), "recoverable\n");
        backup(fixture, "cleanup-recovery");
        ManagedWorktree ready = worktrees.read(fixture.worktree().id());
        CommandIdentity command = identity("worktree/cleanup", "cleanup-recovery", ready);
        Path isolation = new ManagedWorktreePaths(database.dataRoot()).cleanupIsolation(ready);
        ManagedWorktreeService crashing = serviceWithObserver((worktree, root) -> {
            throw new SimulatedProcessStop();
        });

        assertThrows(SimulatedProcessStop.class, () -> crashing.cleanup(command, ready.id()));
        assertEquals(ManagedWorktreeState.CLEANING, worktrees.read(ready.id()).state());
        assertFalse(Files.exists(ready.executionRoot()));
        assertTrue(Files.isDirectory(isolation));

        ManagedWorktree recovered = serviceWithObserver((worktree, root) -> {}).cleanup(command, ready.id());
        assertEquals(ManagedWorktreeState.CLEANED, recovered.state());
        assertFalse(Files.exists(isolation));
        assertFalse(Files.exists(ready.executionRoot()));
    }

    @Test
    void cleanupObserverFailureIsUnknownAndCannotBeRetriedAutomatically() throws Exception {
        WorktreeFixture fixture = provision("cleanup-observer");
        changeTrackedFile(fixture.worktree(), "observer\n");
        backup(fixture, "cleanup-observer");
        ManagedWorktree ready = worktrees.read(fixture.worktree().id());
        Path isolation = new ManagedWorktreePaths(database.dataRoot()).cleanupIsolation(ready);
        ManagedWorktreeService failing = serviceWithObserver((worktree, root) -> {
            throw new IllegalStateException("observer failed");
        });

        assertThrows(
                IllegalStateException.class,
                () -> failing.cleanup(identity("worktree/cleanup", "cleanup-observer", ready), ready.id()));

        ManagedWorktree unknown = worktrees.read(ready.id());
        assertEquals(ManagedWorktreeState.UNKNOWN_OUTCOME, unknown.state());
        assertFalse(Files.exists(ready.executionRoot()));
        assertTrue(Files.isDirectory(isolation));
        assertThrows(
                PersistenceException.class,
                () -> worktrees.cleanup(identity("worktree/cleanup", "cleanup-observer-retry", unknown), unknown.id()));
    }

    @Test
    void cleanupDeleteFailureIsUnknownAndPreservesIsolationForInspection() throws Exception {
        WorktreeFixture fixture = provision("cleanup-delete");
        changeTrackedFile(fixture.worktree(), "delete\n");
        backup(fixture, "cleanup-delete");
        ManagedWorktree ready = worktrees.read(fixture.worktree().id());
        CommandIdentity command = identity("worktree/cleanup", "cleanup-delete", ready);
        Path isolation = new ManagedWorktreePaths(database.dataRoot()).cleanupIsolation(ready);
        sandbox.reject("worktree-cleanup");

        assertThrows(PersistenceException.class, () -> worktrees.cleanup(command, ready.id()));

        ManagedWorktree unknown = worktrees.read(ready.id());
        assertEquals(ManagedWorktreeState.UNKNOWN_OUTCOME, unknown.state());
        assertTrue(Files.isDirectory(isolation));
        assertFalse(Files.exists(ready.executionRoot()));
        sandbox.clearControls();
        assertThrows(PersistenceException.class, () -> worktrees.cleanup(command, ready.id()));
    }

    @Test
    void artifactCommandsRejectIdempotencyConflictAndRevisionDrift() throws Exception {
        WorktreeFixture fixture = provision("artifact-command");
        changeTrackedFile(fixture.worktree(), "artifact\n");
        ManagedWorktree current = worktrees.read(fixture.worktree().id());
        CommandIdentity command = identity("worktree/patch/export", "artifact-command", current);
        ManagedWorktreeArtifact created = worktrees.exportPatch(command, current.id());

        assertEquals(created, worktrees.exportPatch(command, current.id()));
        CommandIdentity conflict = new CommandIdentity(
                command.method(),
                command.idempotencyKey(),
                command.expectedRevision(),
                json.encode(Map.of("x", 1)).sha256());
        assertThrows(PersistenceException.class, () -> worktrees.exportPatch(conflict, current.id()));

        advanceWorktreeRevision(current);
        CommandIdentity stale = identity("worktree/backup", "artifact-stale", current);
        assertThrows(PersistenceException.class, () -> worktrees.backup(stale, current.id()));
    }

    @Test
    void cleanup拒绝子Thread仍有活动Turn() throws Exception {
        WorktreeFixture fixture = provision("active-turn");
        changeTrackedFile(fixture.worktree(), "active\n");
        backup(fixture, "active-turn");
        ManagedWorktree ready = worktrees.read(fixture.worktree().id());
        startTurn(fixture.child(), ready.executionRoot(), "active-child");

        assertThrows(
                PersistenceException.class,
                () -> worktrees.cleanup(identity("worktree/cleanup", "active-turn", ready), ready.id()));

        assertEquals(ManagedWorktreeState.READY, worktrees.read(ready.id()).state());
        assertTrue(Files.isDirectory(ready.executionRoot()));
    }

    @Test
    void artifact拒绝缺失Worktree非GitWorkspace和未注册Worktree() throws Exception {
        assertThrows(
                PersistenceException.class,
                () -> worktrees.exportPatch(
                        identity("worktree/patch/export", "missing-worktree", Map.of()),
                        new WorktreeId(UUID.randomUUID())));

        WorktreeFixture nonGit = provision("non-git");
        Files.move(repository.resolve(".git"), repository.resolve(".git-hidden"));
        assertThrows(PersistenceException.class, () -> exportPatch(nonGit, "non-git"));

        Path secondRepository = initializeRepository(temporaryDirectory.resolve("second-repository"));
        var secondWorkspace = createWorkspace("second", secondRepository);
        var secondParent = createRootThread(secondWorkspace, "second-parent");
        WorktreeFixture unregistered = provision(secondParent, "unregistered");
        git(
                secondRepository,
                "worktree",
                "remove",
                "--force",
                unregistered.worktree().executionRoot().toString());
        assertThrows(PersistenceException.class, () -> exportPatch(unregistered, "unregistered"));
    }

    @Test
    void cleanup拒绝已占用的隔离目录() throws Exception {
        WorktreeFixture fixture = provision("occupied-isolation");
        changeTrackedFile(fixture.worktree(), "occupied\n");
        backup(fixture, "occupied-isolation");
        ManagedWorktree ready = worktrees.read(fixture.worktree().id());
        Path isolation = new ManagedWorktreePaths(database.dataRoot()).cleanupIsolation(ready);
        Files.createDirectories(isolation);

        assertThrows(
                PersistenceException.class,
                () -> worktrees.cleanup(identity("worktree/cleanup", "occupied-isolation", ready), ready.id()));

        assertEquals(
                ManagedWorktreeState.UNKNOWN_OUTCOME, worktrees.read(ready.id()).state());
        assertTrue(Files.isDirectory(ready.executionRoot()));
    }

    @Test
    void cleanup恢复时发现Worktree已消失会标记未知结果() throws Exception {
        WorktreeFixture fixture = provision("missing-after-isolation");
        changeTrackedFile(fixture.worktree(), "missing\n");
        backup(fixture, "missing-after-isolation");
        ManagedWorktree ready = worktrees.read(fixture.worktree().id());
        CommandIdentity command = identity("worktree/cleanup", "missing-after-isolation", ready);
        Path isolation = new ManagedWorktreePaths(database.dataRoot()).cleanupIsolation(ready);
        ManagedWorktreeService crashing = serviceWithObserver((worktree, root) -> {
            throw new SimulatedProcessStop();
        });
        assertThrows(SimulatedProcessStop.class, () -> crashing.cleanup(command, ready.id()));
        git(repository, "worktree", "remove", "--force", isolation.toString());

        assertThrows(PersistenceException.class, () -> worktrees.cleanup(command, ready.id()));

        assertEquals(
                ManagedWorktreeState.UNKNOWN_OUTCOME, worktrees.read(ready.id()).state());
        assertFalse(Files.exists(isolation));
    }

    @Test
    void cleanup校验期间Revision漂移会拒绝提交校验状态() throws Exception {
        WorktreeFixture fixture = provision("verification-revision");
        changeTrackedFile(fixture.worktree(), "revision\n");
        backup(fixture, "verification-revision");
        ManagedWorktree ready = worktrees.read(fixture.worktree().id());
        ManagedWorktreeService drifting = serviceWithObserver((worktree, root) -> {
            try {
                advanceWorktreeRevision(worktree);
            } catch (Exception failure) {
                throw new AssertionError(failure);
            }
        });

        assertThrows(
                PersistenceException.class,
                () -> drifting.cleanup(identity("worktree/cleanup", "verification-revision", ready), ready.id()));

        assertEquals(ManagedWorktreeState.CLEANING, worktrees.read(ready.id()).state());
    }

    @Test
    void cleanup隔离或删除后出现残留均失败关闭() throws Exception {
        WorktreeFixture isolationFixture = provision("isolation-remnant");
        changeTrackedFile(isolationFixture.worktree(), "isolation\n");
        backup(isolationFixture, "isolation-remnant");
        ManagedWorktree isolationReady =
                worktrees.read(isolationFixture.worktree().id());
        sandbox.after(command -> {
            if (command.id().equals("worktree-isolate")) {
                Files.createDirectories(isolationReady.executionRoot());
            }
        });
        assertThrows(
                PersistenceException.class,
                () -> worktrees.cleanup(
                        identity("worktree/cleanup", "isolation-remnant", isolationReady), isolationReady.id()));
        assertEquals(
                ManagedWorktreeState.UNKNOWN_OUTCOME,
                worktrees.read(isolationReady.id()).state());

        sandbox.clearControls();
        WorktreeFixture cleanupFixture = provision("cleanup-remnant");
        changeTrackedFile(cleanupFixture.worktree(), "cleanup\n");
        backup(cleanupFixture, "cleanup-remnant");
        ManagedWorktree cleanupReady = worktrees.read(cleanupFixture.worktree().id());
        Path isolation = new ManagedWorktreePaths(database.dataRoot()).cleanupIsolation(cleanupReady);
        sandbox.after(command -> {
            if (command.id().equals("worktree-cleanup")) {
                Files.createDirectories(isolation);
            }
        });

        assertThrows(
                PersistenceException.class,
                () -> worktrees.cleanup(
                        identity("worktree/cleanup", "cleanup-remnant", cleanupReady), cleanupReady.id()));
        assertEquals(
                ManagedWorktreeState.UNKNOWN_OUTCOME,
                worktrees.read(cleanupReady.id()).state());
        assertTrue(Files.isDirectory(isolation));
    }

    private static final class SimulatedProcessStop extends Error {
        private static final long serialVersionUID = 1L;
    }
}
