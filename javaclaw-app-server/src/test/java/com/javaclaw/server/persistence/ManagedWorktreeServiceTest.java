package com.javaclaw.server.persistence;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.javaclaw.api.AgentProfileRef;
import com.javaclaw.api.AgentTurn;
import com.javaclaw.api.AttachmentScope;
import com.javaclaw.api.ConversationThread;
import com.javaclaw.api.CorePayloads;
import com.javaclaw.api.FilePermission;
import com.javaclaw.api.ManagedWorktree;
import com.javaclaw.api.ManagedWorktreeArtifact;
import com.javaclaw.api.ManagedWorktreeState;
import com.javaclaw.api.MessageRole;
import com.javaclaw.api.PermissionProfile;
import com.javaclaw.api.PermissionProfileRef;
import com.javaclaw.api.ProviderRef;
import com.javaclaw.api.ThreadExecutionIntent;
import com.javaclaw.api.ThreadId;
import com.javaclaw.api.TurnBudget;
import com.javaclaw.api.TurnStatus;
import com.javaclaw.api.Workspace;
import com.javaclaw.api.WorktreeId;
import com.javaclaw.protocol.CanonicalJson;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ManagedWorktreeServiceTest {
    private static final Instant NOW = Instant.parse("2026-09-01T00:00:00Z");
    private static final Path GIT = Path.of("/usr/bin/git");

    @TempDir
    Path temporaryDirectory;

    private CanonicalJson json;
    private H2Database database;
    private CoreCommandService core;
    private AttachmentService attachments;
    private ManagedWorktreeService worktrees;
    private Path repository;
    private Workspace workspace;
    private ConversationThread parent;
    private AgentTurn parentTurn;
    private int sequence;

    @BeforeEach
    void createRepositoryAndDataV5() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(Files.isExecutable(GIT));
        repository = temporaryDirectory.resolve("repository");
        Files.createDirectories(repository);
        git(repository, "init");
        git(repository, "config", "user.email", "tests@javaclaw.invalid");
        git(repository, "config", "user.name", "JavaClaw Tests");
        Files.writeString(repository.resolve("tracked.txt"), "base\n", StandardCharsets.UTF_8);
        Files.writeString(repository.resolve("staged.txt"), "base\n", StandardCharsets.UTF_8);
        Files.write(repository.resolve("binary.dat"), new byte[] {0, 1, 2, 3});
        Files.writeString(repository.resolve(".gitignore"), "*.ignored\n", StandardCharsets.UTF_8);
        git(repository, "add", "--all");
        git(repository, "commit", "-m", "baseline");

        json = new CanonicalJson();
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        database = new H2Database(temporaryDirectory.resolve("data-v5"));
        database.initialize();
        core = new CoreCommandService(database, json, clock);
        PermissionProfileService testProfiles = new PermissionProfileService(database, json, clock);
        testProfiles.installStandardProfile();
        attachments = new AttachmentService(database, json, clock);
        worktrees = new ManagedWorktreeService(
                database, attachments, json, clock, new ManagedWorktreeFailureTestSupport.ControlledProcessSandbox());
        workspace = core.createWorkspace(identity("workspace/create", "workspace", Map.of()), "Workspace", repository);
        parent = core.createThread(
                identity("thread/create", "parent", Map.of()),
                workspace.id(),
                Optional.empty(),
                ThreadExecutionIntent.WORKSPACE,
                "Parent");
        parentTurn = core.startTurn(
                identity("turn/start", "parent-turn", parent),
                new TurnStartRequest(
                        parent.id(),
                        new TurnBudget(1_000, 1_000, 10, 4, Duration.ofMinutes(5)),
                        new AgentProfileRef("test-profile", 1),
                        new ProviderRef("test-provider", 1, "test-model"),
                        new PermissionProfileRef("standard", 1),
                        repository,
                        json.encode(Map.of()),
                        new com.javaclaw.api.ToolCatalogSnapshot(
                                com.javaclaw.api.TurnId.random(),
                                1,
                                java.util.List.of(),
                                testProfiles.require(PermissionProfileService.STANDARD_PROFILE_ID, 1),
                                NOW),
                        new CorePayloads.Message(MessageRole.USER, "test", java.util.List.of(), Optional.empty()),
                        Optional.empty()));
    }

    @Test
    void patchCapturesAllGitStatesWithoutChangingRealIndex() throws Exception {
        WorktreeFixture fixture = provision("patch");
        prepareMixedChanges(fixture.worktree().executionRoot());
        byte[] indexBefore = indexBytes(fixture.worktree().executionRoot());

        ManagedWorktreeArtifact artifact = worktrees.exportPatch(
                identity("worktree/patch/export", "patch-export", fixture.worktree()),
                fixture.worktree().id());

        byte[] patch = attachments
                .read(
                        AttachmentScope.workspace(fixture.worktree().workspaceId()),
                        artifact.attachment().digest())
                .content();
        String text = new String(patch, StandardCharsets.ISO_8859_1);
        assertTrue(text.contains("renamed.txt"));
        assertTrue(text.contains("staged.txt"));
        assertTrue(text.contains("untracked.txt"));
        assertTrue(text.contains("GIT binary patch"));
        assertFalse(text.contains("secret.ignored"));
        assertArrayEquals(indexBefore, indexBytes(fixture.worktree().executionRoot()));
    }

    @Test
    void conflictingApplyLeavesParentFilesAndIndexUntouched() throws Exception {
        WorktreeFixture fixture = provision("conflict");
        Files.writeString(fixture.worktree().executionRoot().resolve("tracked.txt"), "child\n");
        ManagedWorktreeArtifact patch = worktrees.exportPatch(
                identity("worktree/patch/export", "conflict-export", fixture.worktree()),
                fixture.worktree().id());
        Files.writeString(repository.resolve("tracked.txt"), "parent\n");
        byte[] indexBefore = indexBytes(repository);

        PersistenceException conflict = assertThrows(
                PersistenceException.class,
                () -> worktrees.apply(
                        "apply-conflict",
                        parentTurn.id(),
                        fixture.worktree().id(),
                        patch.attachment().digest()));

        assertEquals(PersistenceException.Kind.REVISION_CONFLICT, conflict.kind());
        assertEquals("parent\n", Files.readString(repository.resolve("tracked.txt")));
        assertArrayEquals(indexBefore, indexBytes(repository));
        assertEquals(
                ManagedWorktreeState.CONFLICTED,
                worktrees.read(fixture.worktree().id()).state());
    }

    @Test
    void parentTurnAppliesExactAttachmentAndRetryIsIdempotent() throws Exception {
        WorktreeFixture fixture = provision("apply");
        Files.writeString(fixture.worktree().executionRoot().resolve("tracked.txt"), "child\n");
        Files.writeString(fixture.worktree().executionRoot().resolve("new.txt"), "new\n");
        ManagedWorktreeArtifact patch = worktrees.exportPatch(
                identity("worktree/patch/export", "apply-export", fixture.worktree()),
                fixture.worktree().id());

        ManagedWorktree applied = worktrees.apply(
                "apply-success",
                parentTurn.id(),
                fixture.worktree().id(),
                patch.attachment().digest());
        ManagedWorktree retried = worktrees.apply(
                "apply-success",
                parentTurn.id(),
                fixture.worktree().id(),
                patch.attachment().digest());

        assertEquals(ManagedWorktreeState.APPLIED, applied.state());
        assertEquals(applied, retried);
        assertEquals("child\n", Files.readString(repository.resolve("tracked.txt")));
        assertEquals("new\n", Files.readString(repository.resolve("new.txt")));
    }

    @Test
    void cleanupRejectsChangesAfterBackupThenSucceedsWithFreshBackup() throws Exception {
        WorktreeFixture fixture = provision("cleanup");
        Path tracked = fixture.worktree().executionRoot().resolve("tracked.txt");
        Files.writeString(tracked, "first\n");
        backup("backup-first", fixture.worktree().id());
        Files.writeString(tracked, "second\n");
        ManagedWorktree afterBackup = worktrees.read(fixture.worktree().id());

        assertThrows(
                PersistenceException.class,
                () -> worktrees.cleanup(identity("worktree/cleanup", "cleanup-stale", afterBackup), afterBackup.id()));
        ManagedWorktree failed = worktrees.read(afterBackup.id());
        assertEquals(ManagedWorktreeState.FAILED, failed.state());
        assertTrue(Files.isDirectory(failed.executionRoot()));

        backup("backup-fresh", failed.id());
        ManagedWorktree ready = worktrees.read(failed.id());
        ManagedWorktree cleaned = worktrees.cleanup(identity("worktree/cleanup", "cleanup-success", ready), ready.id());
        assertEquals(ManagedWorktreeState.CLEANED, cleaned.state());
        assertFalse(Files.exists(cleaned.executionRoot()));
    }

    @Test
    void cleanupQuarantinesRootBeforeConcurrentExternalWrite() throws Exception {
        WorktreeFixture fixture = provision("concurrent-cleanup");
        Path executionRoot = fixture.worktree().executionRoot();
        Files.writeString(executionRoot.resolve("tracked.txt"), "backup\n");
        backup("backup-concurrent", fixture.worktree().id());
        ManagedWorktree ready = worktrees.read(fixture.worktree().id());
        CountDownLatch isolated = new CountDownLatch(1);
        CountDownLatch attempted = new CountDownLatch(1);
        AtomicReference<Throwable> writeFailure = new AtomicReference<>();
        worktrees = new ManagedWorktreeService(
                database,
                attachments,
                json,
                Clock.fixed(NOW, ZoneOffset.UTC),
                new ManagedWorktreeFailureTestSupport.ControlledProcessSandbox(),
                (worktree, isolationRoot) -> {
                    isolated.countDown();
                    await(attempted);
                });
        Thread writer = Thread.ofVirtual().start(() -> {
            await(isolated);
            try {
                Files.writeString(executionRoot.resolve("late.txt"), "late\n");
            } catch (Throwable failure) {
                writeFailure.set(failure);
            } finally {
                attempted.countDown();
            }
        });

        ManagedWorktree cleaned =
                worktrees.cleanup(identity("worktree/cleanup", "cleanup-concurrent", ready), ready.id());
        writer.join();

        assertInstanceOf(NoSuchFileException.class, writeFailure.get());
        assertEquals(ManagedWorktreeState.CLEANED, cleaned.state());
        assertFalse(Files.exists(executionRoot));
    }

    @Test
    void onlyIsolatedWriteChildIsProvisionedAndRestartReconcilesMissingBinding() {
        ConversationThread readOnly = child("read-only", ThreadExecutionIntent.READ_ONLY);
        ConversationThread isolated = child("reconcile", ThreadExecutionIntent.ISOLATED_WRITE);
        assertTrue(worktrees.findByChild(readOnly.id()).isEmpty());
        assertTrue(worktrees.findByChild(isolated.id()).isEmpty());

        worktrees.reconcileProvisioning();

        assertTrue(worktrees.findByChild(readOnly.id()).isEmpty());
        assertEquals(
                isolated.id(),
                worktrees.findByChild(isolated.id()).orElseThrow().childThreadId());
    }

    @Test
    void permissionsRebaseWorkspaceRootsToManagedExecutionRoot() {
        WorktreeFixture fixture = provision("permission-root");
        PermissionProfileService permissions =
                new PermissionProfileService(database, json, Clock.fixed(NOW, ZoneOffset.UTC));
        permissions.installStandardProfile();
        PermissionProfile clone = permissions.cloneProfile(
                identity("permissionProfile/clone", "permission-clone", Map.of("id", "isolated-test")),
                new com.javaclaw.api.PermissionProfileRef(PermissionProfileService.STANDARD_PROFILE_ID, 1),
                "isolated-test");
        PermissionProfile configured = new PermissionProfile(
                clone.id(),
                2,
                new FilePermission(java.util.List.of(repository), java.util.List.of(repository), true, false),
                clone.network(),
                clone.processes(),
                clone.tools(),
                clone.resources());
        permissions.update(
                new CommandIdentity(
                        "permissionProfile/update",
                        "permission-update",
                        1,
                        json.encode(configured).sha256()),
                configured);

        PermissionProfile effective = permissions.resolveForExecution(
                configured.id(),
                configured.version(),
                workspace,
                fixture.worktree().executionRoot(),
                true);

        assertEquals(
                java.util.List.of(fixture.worktree().executionRoot()),
                effective.files().readRoots());
        assertEquals(
                java.util.List.of(fixture.worktree().executionRoot()),
                effective.files().writeRoots());
        assertFalse(effective.files().writeRoots().contains(repository));
    }

    @Test
    void lifecycleQueriesAndTerminalTransitionsKeepAuthorityInH2() {
        WorktreeFixture fixture = provision("lifecycle");

        assertEquals(java.util.List.of(fixture.worktree()), worktrees.list(workspace.id(), false));
        assertEquals(
                fixture.worktree(),
                worktrees.requireForExecution(fixture.child().id()));
        worktrees.markRunning(fixture.child().id());
        worktrees.markRunning(fixture.child().id());
        assertEquals(
                ManagedWorktreeState.RUNNING,
                worktrees.read(fixture.worktree().id()).state());

        worktrees.finishExecution(fixture.child().id(), TurnStatus.COMPLETED);
        assertEquals(
                ManagedWorktreeState.COMPLETED,
                worktrees.read(fixture.worktree().id()).state());
        worktrees.markRunning(fixture.child().id());
        worktrees.finishExecution(fixture.child().id(), TurnStatus.FAILED);
        worktrees.markRunning(fixture.child().id());
        worktrees.finishExecution(fixture.child().id(), TurnStatus.CANCELLED);
        worktrees.finishExecution(fixture.child().id(), TurnStatus.COMPLETED);
        assertEquals(
                ManagedWorktreeState.INTERRUPTED,
                worktrees.read(fixture.worktree().id()).state());

        ThreadId unbound = ThreadId.random();
        worktrees.markRunning(unbound);
        worktrees.finishExecution(unbound, TurnStatus.COMPLETED);
        assertThrows(IllegalArgumentException.class, () -> worktrees.finishExecution(unbound, TurnStatus.QUEUED));
        assertThrows(IllegalArgumentException.class, () -> worktrees.finishExecution(unbound, TurnStatus.RUNNING));
        assertThrows(IllegalArgumentException.class, () -> worktrees.finishExecution(unbound, TurnStatus.WAITING));
    }

    @Test
    void interruptCancelsActiveTurnAtomicallyAndRetryReturnsOriginalResult() {
        WorktreeFixture fixture = provision("interrupt-active");
        AgentTurn childTurn = startChildTurn(fixture);
        worktrees.markRunning(fixture.child().id());
        ManagedWorktree running = worktrees.read(fixture.worktree().id());
        CommandIdentity command = identity("worktree/interrupt", "interrupt-active", running);

        ManagedWorktreeInterruptResult interrupted = worktrees.interrupt(command, running.id(), "用户停止隔离任务");
        ManagedWorktreeInterruptResult retried = worktrees.interrupt(command, running.id(), "用户停止隔离任务");

        assertEquals(interrupted, retried);
        assertEquals(ManagedWorktreeState.INTERRUPTED, interrupted.worktree().state());
        assertEquals(Optional.of(childTurn.id()), interrupted.turnId());
        assertEquals(
                childTurn.revision() + 1,
                core.findTurn(childTurn.id()).orElseThrow().revision());
    }

    @Test
    void interruptWithoutActiveTurnRejectsStaleRevisionAndInvalidReasons() {
        WorktreeFixture fixture = provision("interrupt-idle");
        ManagedWorktree ready = fixture.worktree();
        CommandIdentity command = identity("worktree/interrupt", "interrupt-idle", ready);

        ManagedWorktreeInterruptResult interrupted = worktrees.interrupt(command, ready.id(), "停止待运行任务");

        assertTrue(interrupted.turnId().isEmpty());
        assertThrows(
                PersistenceException.class,
                () -> worktrees.interrupt(
                        identity("worktree/interrupt", "interrupt-terminal", interrupted.worktree()),
                        ready.id(),
                        "重复停止"));
        CommandIdentity stale = new CommandIdentity(
                "worktree/interrupt",
                "interrupt-stale",
                1,
                json.encode(Map.of("reason", "stale")).sha256());
        assertThrows(PersistenceException.class, () -> worktrees.interrupt(stale, ready.id(), "stale"));
        assertThrows(PersistenceException.class, () -> worktrees.interrupt(command, ready.id(), "  "));
        assertThrows(PersistenceException.class, () -> worktrees.interrupt(command, ready.id(), "x".repeat(501)));
    }

    @Test
    void provisionIsIdempotentAndRejectsConflictingIdentityOrOccupiedDestination() throws Exception {
        ConversationThread child = child("provision-idempotent", ThreadExecutionIntent.ISOLATED_WRITE);
        CommandIdentity command = new CommandIdentity(
                "worktree/provision",
                "same-provision",
                0,
                json.encode(Map.of("child", child.id())).sha256());
        ManagedWorktree created = worktrees.provisionForChild(command, workspace.id(), parent.id(), child.id());

        assertEquals(created, worktrees.provisionForChild(command, workspace.id(), parent.id(), child.id()));
        CommandIdentity another = new CommandIdentity(
                "worktree/provision",
                "another-provision",
                0,
                json.encode(Map.of("child", child.id())).sha256());
        assertEquals(created, worktrees.provisionForChild(another, workspace.id(), parent.id(), child.id()));
        CommandIdentity conflict = new CommandIdentity(
                "worktree/provision",
                "same-provision",
                0,
                json.encode(Map.of("child", "different")).sha256());
        assertThrows(
                PersistenceException.class,
                () -> worktrees.provisionForChild(conflict, workspace.id(), parent.id(), child.id()));

        ConversationThread occupied = child("occupied", ThreadExecutionIntent.ISOLATED_WRITE);
        WorktreeId occupiedId = ManagedWorktreePolicy.deterministicId(occupied.id());
        Path destination = database.dataRoot()
                .resolve("worktrees")
                .resolve(workspace.id().toString())
                .resolve(occupiedId.toString());
        Files.createDirectories(destination);
        assertThrows(
                PersistenceException.class,
                () -> worktrees.provisionForChild(
                        identity("worktree/provision", "occupied", occupied),
                        workspace.id(),
                        parent.id(),
                        occupied.id()));
    }

    @Test
    void missingOrCleanedBindingsCannotBecomeExecutionRoots() {
        ThreadId missing = ThreadId.random();
        assertThrows(PersistenceException.class, () -> worktrees.requireForExecution(missing));
        assertThrows(PersistenceException.class, () -> worktrees.read(new WorktreeId(java.util.UUID.randomUUID())));

        WorktreeFixture fixture = provision("query-cleaned");
        backup("backup-query-cleaned", fixture.worktree().id());
        ManagedWorktree ready = worktrees.read(fixture.worktree().id());
        ManagedWorktree cleaned = worktrees.cleanup(identity("worktree/cleanup", "query-cleaned", ready), ready.id());

        assertTrue(worktrees.findByChild(fixture.child().id()).isEmpty());
        assertTrue(worktrees.list(workspace.id(), false).isEmpty());
        assertEquals(java.util.List.of(cleaned), worktrees.list(workspace.id(), true));
        assertThrows(
                PersistenceException.class,
                () -> worktrees.requireForExecution(fixture.child().id()));
    }

    @Test
    void artifactAndCleanupRetriesReturnTheOriginalDurableResult() throws Exception {
        WorktreeFixture fixture = provision("artifact-retry");
        Files.writeString(fixture.worktree().executionRoot().resolve("tracked.txt"), "changed\n");
        CommandIdentity patchCommand = identity("worktree/patch/export", "patch-retry", fixture.worktree());
        ManagedWorktreeArtifact patch =
                worktrees.exportPatch(patchCommand, fixture.worktree().id());
        assertEquals(
                patch, worktrees.exportPatch(patchCommand, fixture.worktree().id()));

        assertThrows(
                PersistenceException.class,
                () -> worktrees.cleanup(
                        identity("worktree/cleanup", "without-backup", fixture.worktree()),
                        fixture.worktree().id()));
        CommandIdentity backupCommand = identity("worktree/backup", "backup-retry", fixture.worktree());
        ManagedWorktreeArtifact backup =
                worktrees.backup(backupCommand, fixture.worktree().id());
        assertEquals(backup, worktrees.backup(backupCommand, fixture.worktree().id()));

        ManagedWorktree ready = worktrees.read(fixture.worktree().id());
        CommandIdentity cleanupCommand = identity("worktree/cleanup", "cleanup-retry", ready);
        ManagedWorktree cleaned = worktrees.cleanup(cleanupCommand, ready.id());
        assertEquals(cleaned, worktrees.cleanup(cleanupCommand, ready.id()));
    }

    private WorktreeFixture provision(String suffix) {
        ConversationThread child = child(suffix, ThreadExecutionIntent.ISOLATED_WRITE);
        ManagedWorktree worktree = worktrees.provisionForChild(
                identity("worktree/provision", "provision-" + suffix, child), workspace.id(), parent.id(), child.id());
        return new WorktreeFixture(child, worktree);
    }

    private AgentTurn startChildTurn(WorktreeFixture fixture) {
        return core.startTurn(
                identity("turn/start", "child-turn", fixture.child()),
                new TurnStartRequest(
                        fixture.child().id(),
                        new TurnBudget(1_000, 1_000, 10, 4, Duration.ofMinutes(5)),
                        parentTurn.profile(),
                        parentTurn.provider(),
                        parentTurn.permissionProfile(),
                        fixture.worktree().executionRoot(),
                        json.encode(Map.of()),
                        new com.javaclaw.api.ToolCatalogSnapshot(
                                com.javaclaw.api.TurnId.random(),
                                1,
                                java.util.List.of(),
                                new PermissionProfileService(database, json, Clock.fixed(NOW, ZoneOffset.UTC))
                                        .require(PermissionProfileService.STANDARD_PROFILE_ID, 1),
                                NOW),
                        new CorePayloads.Message(MessageRole.USER, "child", java.util.List.of(), Optional.empty()),
                        Optional.empty()));
    }

    private ConversationThread child(String suffix, ThreadExecutionIntent intent) {
        return core.createThread(
                identity("thread/create", "child-" + suffix, Map.of("executionIntent", intent.name())),
                workspace.id(),
                Optional.of(parent.id()),
                intent,
                "Child " + suffix);
    }

    private void prepareMixedChanges(Path root) throws Exception {
        Files.move(root.resolve("tracked.txt"), root.resolve("renamed.txt"));
        Files.write(root.resolve("binary.dat"), new byte[] {0, 9, 8, 7, 6});
        Files.writeString(root.resolve("staged.txt"), "staged\n", StandardCharsets.UTF_8);
        git(root, "add", "staged.txt");
        Files.writeString(root.resolve("staged.txt"), "staged and unstaged\n", StandardCharsets.UTF_8);
        Files.writeString(root.resolve("untracked.txt"), "new\n", StandardCharsets.UTF_8);
        Files.writeString(root.resolve("secret.ignored"), "ignore\n", StandardCharsets.UTF_8);
    }

    private void backup(String key, com.javaclaw.api.WorktreeId id) {
        ManagedWorktree current = worktrees.read(id);
        worktrees.backup(identity("worktree/backup", key, current), id);
    }

    private byte[] indexBytes(Path checkout) throws Exception {
        String reported = git(checkout, "rev-parse", "--git-path", "index").strip();
        Path index = Path.of(reported);
        return Files.readAllBytes(
                index.isAbsolute() ? index : checkout.resolve(index).normalize());
    }

    private CommandIdentity identity(String method, String key, Object payload) {
        long expected = payload instanceof ManagedWorktree worktree ? worktree.revision() : 0;
        return new CommandIdentity(
                method, key + "-" + sequence++, expected, json.encode(payload).sha256());
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("等待 cleanup 并发测试同步超时");
            }
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("cleanup 并发测试被中断", failure);
        }
    }

    private static String git(Path directory, String... arguments) throws Exception {
        String[] command = new String[arguments.length + 1];
        command[0] = GIT.toString();
        System.arraycopy(arguments, 0, command, 1, arguments.length);
        Process process =
                new ProcessBuilder(command).directory(directory.toFile()).start();
        byte[] output = process.getInputStream().readAllBytes();
        byte[] error = process.getErrorStream().readAllBytes();
        if (process.waitFor() != 0) {
            throw new IllegalStateException(new String(error, StandardCharsets.UTF_8));
        }
        return new String(output, StandardCharsets.UTF_8);
    }

    private record WorktreeFixture(ConversationThread child, ManagedWorktree worktree) {}
}
