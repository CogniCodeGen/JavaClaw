package com.javaclaw.server.collaboration;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.javaclaw.agent.collaboration.CollaborationGateway;
import com.javaclaw.agent.conversation.ProfileRepository;
import com.javaclaw.agent.conversation.ProfileService;
import com.javaclaw.agent.runtime.DefaultAgentRuntime;
import com.javaclaw.agent.runtime.RuntimeEventBus;
import com.javaclaw.core.api.ApprovalPolicy;
import com.javaclaw.core.api.ProfileKind;
import com.javaclaw.core.api.TurnConfig;
import com.javaclaw.core.api.TurnInput;
import com.javaclaw.core.api.TurnStartCommand;
import com.javaclaw.sandbox.api.SandboxCommand;
import com.javaclaw.sandbox.api.SandboxExecutor;
import com.javaclaw.sandbox.api.SandboxMode;
import com.javaclaw.sandbox.api.SandboxPolicy;
import com.javaclaw.sandbox.api.SandboxResult;
import com.javaclaw.server.bootstrap.ServerComponentGraph;
import com.javaclaw.server.persistence.H2CollaborationRepository;
import com.javaclaw.server.persistence.H2Persistence;
import com.javaclaw.server.persistence.H2ProfileRepository;
import com.javaclaw.server.persistence.H2WorktreeRepository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CollaborationServiceGitTest {
    @TempDir
    Path temporary;

    @Test
    void dirtyWorkspaceIsSnapshottedAndAppliedWithoutTouchingTheUserIndex() throws Exception {
        Path git = controlledGit();
        if (Boolean.getBoolean("javaclaw.require.native.sandbox")) {
            assertNotNull(git, "严格门禁要求 Runner 安装受控 Git，不能跳过工作树测试");
        }
        Assumptions.assumeTrue(git != null, "controlled Git is unavailable on this runner");
        Path workspaceRoot = Files.createDirectories(temporary.resolve("workspace"));
        git(git, workspaceRoot, "init", "--quiet");
        Files.writeString(workspaceRoot.resolve("tracked.txt"), "committed\n");
        git(git, workspaceRoot, "add", "tracked.txt");
        git(
                git,
                workspaceRoot,
                "-c",
                "user.name=JavaClaw Test",
                "-c",
                "user.email=test@localhost",
                "commit",
                "--quiet",
                "-m",
                "base");

        // Neither change is staged. Both must become part of the synthetic child baseline.
        Files.writeString(workspaceRoot.resolve("tracked.txt"), "parent dirty\n");
        Files.writeString(workspaceRoot.resolve("untracked.txt"), "parent untracked\n");
        assertEquals(
                "", git(git, workspaceRoot, "diff", "--cached", "--name-only").strip());

        Path data = temporary.resolve("data");
        H2Persistence store = new H2Persistence(data);
        var ceiling = ServerComponentGraph.hostCeiling(data);
        ProfileService profiles =
                new ProfileService(new H2ProfileRepository(store.database()), ceiling.protectedRoots());
        profiles.put(
                new ProfileRepository.ProfileDraft(
                        "profile_subagent",
                        "Subagent",
                        ProfileKind.SUBAGENT,
                        "fake",
                        "fake",
                        "",
                        Set.of(),
                        SandboxMode.WORKSPACE_WRITE,
                        4,
                        4,
                        Map.of()),
                0,
                "profile-1");
        try (store;
                DefaultAgentRuntime threads = new DefaultAgentRuntime(
                        store.runtime(),
                        (context, sink) -> {
                            if (context.thread().parentThreadId() == null) {
                                new CountDownLatch(1).await();
                            }
                        },
                        new RuntimeEventBus())) {
            var workspace = threads.createWorkspace("workspace", workspaceRoot, "workspace-1");
            var parent = threads.startThread(workspace.id(), "parent");
            // 子任务只从活动父 Turn 分配预算；只读父任务不会与隔离写工作树共享写租约。
            threads.startTurn(new TurnStartCommand(
                    parent.id(),
                    List.of(new TurnInput.Text("coordinate")),
                    new TurnConfig(
                            "fake",
                            "fake",
                            "medium",
                            workspaceRoot,
                            SandboxPolicy.readOnly(Set.of(workspaceRoot), Set.of()),
                            ApprovalPolicy.ON_RISK,
                            Set.of(),
                            Map.of()),
                    "parent-turn"));
            H2WorktreeRepository worktrees = new H2WorktreeRepository(store.database());
            CollaborationService collaboration = new CollaborationService(
                    threads,
                    threads,
                    threads,
                    threads,
                    profiles,
                    new H2CollaborationRepository(store.database()),
                    worktrees,
                    store.attachments(),
                    new DirectSandbox(),
                    ceiling,
                    temporary.resolve("cache/worktrees"),
                    List.of(git.toString()));

            var child = collaboration.spawn(new CollaborationGateway.SpawnRequest(
                    parent.id(), "edit in isolation", true, "profile_subagent", "spawn-1"));
            assertTrue(collaboration
                    .waitForTerminal(child.id(), Duration.ofSeconds(5))
                    .isPresent());
            var worktree = worktrees.findByChild(child.id()).orElseThrow();
            assertTrue(Files.isDirectory(worktree.path()));
            assertEquals("parent dirty\n", Files.readString(worktree.path().resolve("tracked.txt")));
            assertEquals("parent untracked\n", Files.readString(worktree.path().resolve("untracked.txt")));

            Files.writeString(worktree.path().resolve("tracked.txt"), "child result\n");
            Files.writeString(worktree.path().resolve("child.txt"), "new child file\n");
            var diff = collaboration.diff(child.id());
            assertEquals("READY", diff.status());
            assertNotNull(diff.patchAttachmentSha256());

            var applied = collaboration.apply(parent.id(), child.id(), "apply-1");
            assertTrue(Set.of("APPLIED", "APPLIED_CLEANUP_REQUIRED").contains(applied.status()));
            assertEquals("child result\n", Files.readString(workspaceRoot.resolve("tracked.txt")));
            assertEquals("new child file\n", Files.readString(workspaceRoot.resolve("child.txt")));
            assertEquals("parent untracked\n", Files.readString(workspaceRoot.resolve("untracked.txt")));
            assertEquals(
                    "",
                    git(git, workspaceRoot, "diff", "--cached", "--name-only").strip(),
                    "the user's real Git index must remain untouched");
            if ("APPLIED".equals(applied.status())) {
                assertFalse(Files.exists(worktree.path()));
            }

            var conflicting = collaboration.spawn(new CollaborationGateway.SpawnRequest(
                    parent.id(), "conflicting change", true, "profile_subagent", "spawn-conflict"));
            assertTrue(collaboration
                    .waitForTerminal(conflicting.id(), Duration.ofSeconds(5))
                    .isPresent());
            var conflictRoot = worktrees.findByChild(conflicting.id()).orElseThrow();
            Files.writeString(conflictRoot.path().resolve("tracked.txt"), "child conflict\n");
            Files.writeString(workspaceRoot.resolve("tracked.txt"), "parent conflict\n");
            assertEquals(
                    "CONFLICT",
                    collaboration
                            .apply(parent.id(), conflicting.id(), "apply-conflict")
                            .status());
            var conflict = worktrees.findByChild(conflicting.id()).orElseThrow();
            assertEquals(WorktreeRepository.State.CONFLICT, conflict.state());
            assertTrue(Files.exists(conflict.path()));
            assertThrows(IllegalStateException.class, () -> collaboration.assertCanDeleteThread(parent.id()));
            assertThrows(IllegalStateException.class, () -> collaboration.cleanup(conflicting.id(), false));
            assertThrows(
                    IllegalStateException.class,
                    () -> collaboration.exportPatch(conflicting.id(), conflict.revision() - 1));
            assertNotNull(collaboration
                    .exportPatch(conflicting.id(), conflict.revision())
                    .patchAttachmentSha256());
            byte[] parentBeforeCleanup = Files.readAllBytes(workspaceRoot.resolve("tracked.txt"));
            var request = new WorktreeRecoveryUseCases.CleanupRequest(
                    conflicting.id(), conflict.revision(), true, "cleanup-conflict");
            var cleaned = collaboration.cleanup(request);
            assertEquals("CLEANED", cleaned.state());
            assertNotNull(cleaned.backupSha256());
            assertEquals(cleaned, collaboration.cleanup(request));
            assertFalse(Files.exists(conflict.path()));
            org.junit.jupiter.api.Assertions.assertArrayEquals(
                    parentBeforeCleanup, Files.readAllBytes(workspaceRoot.resolve("tracked.txt")));
            assertEquals(
                    "",
                    git(git, workspaceRoot, "diff", "--cached", "--name-only").strip());
            assertEquals(
                    "DISCARDED",
                    collaboration
                            .apply(parent.id(), conflicting.id(), "not-a-merge")
                            .status());
            assertTrue(collaboration.listRecovery(workspace.id()).stream()
                    .anyMatch(value -> value.childThreadId().equals(conflicting.id()) && value.backupSha256() != null));
            collaboration.assertCanDeleteThread(parent.id());
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(
            value = SandboxMode.class,
            names = {"READ_ONLY", "WORKSPACE_WRITE"})
    void nonGitWorkspaceUsesOneWriterAndNarrowsChildrenWhenParentOwnsIt(SandboxMode parentMode) throws Exception {
        Path root = Files.createDirectory(temporary.resolve("non-git")).toRealPath();
        Path data = temporary.resolve("data");
        try (var store = new H2Persistence(data);
                var runtime = new DefaultAgentRuntime(
                        store.runtime(),
                        (context, sink) -> {
                            if (context.thread().parentThreadId() == null) {
                                new CountDownLatch(1).await();
                            }
                        },
                        new RuntimeEventBus())) {
            var ceiling = ServerComponentGraph.hostCeiling(data);
            var profiles = new ProfileService(new H2ProfileRepository(store.database()), ceiling.protectedRoots());
            profiles.put(
                    new ProfileRepository.ProfileDraft(
                            "sub",
                            "子任务",
                            ProfileKind.SUBAGENT,
                            "fake",
                            "fake",
                            "",
                            Set.of(),
                            SandboxMode.WORKSPACE_WRITE,
                            4,
                            4,
                            Map.of()),
                    0,
                    "profile");
            var workspace = runtime.createWorkspace("non-git", root, "workspace");
            var parent = runtime.startThread(workspace.id(), "parent");
            SandboxPolicy policy = parentMode == SandboxMode.READ_ONLY
                    ? SandboxPolicy.readOnly(Set.of(root), Set.of(root.resolve(".git"), root.resolve(".javaclaw")))
                    : SandboxPolicy.workspaceWrite(
                            Set.of(root), Set.of(root), Set.of(root.resolve(".git"), root.resolve(".javaclaw")));
            runtime.startTurn(new TurnStartCommand(
                    parent.id(),
                    List.of(new TurnInput.Text("parent")),
                    new TurnConfig("fake", "fake", "medium", root, policy, ApprovalPolicy.ON_RISK, Set.of(), Map.of()),
                    "parent"));
            var worktrees = new H2WorktreeRepository(store.database());
            var collaboration = new CollaborationService(
                    runtime,
                    runtime,
                    runtime,
                    runtime,
                    profiles,
                    new H2CollaborationRepository(store.database()),
                    worktrees,
                    store.attachments(),
                    command -> {
                        throw new AssertionError("非 Git 任务不能启动 Git");
                    },
                    ceiling,
                    temporary.resolve("cache"),
                    List.of());
            var child = collaboration.spawn(
                    new CollaborationGateway.SpawnRequest(parent.id(), "non-git child", true, "sub", "spawn"));
            var snapshot = collaboration
                    .waitForTerminal(child.id(), Duration.ofSeconds(5))
                    .orElseThrow();
            assertEquals(root, child.workingDirectory());
            assertEquals(
                    parentMode == SandboxMode.READ_ONLY ? SandboxMode.WORKSPACE_WRITE : SandboxMode.READ_ONLY,
                    snapshot.turns().getLast().config().sandboxPolicy().mode());
            assertTrue(worktrees.listRequiringCleanup().isEmpty());
        }
    }

    private static Path controlledGit() {
        for (Path candidate : List.of(
                Path.of("/usr/bin/git"),
                Path.of("/bin/git"),
                Path.of("C:/Program Files/Git/cmd/git.exe"),
                Path.of("C:/Program Files/Git/bin/git.exe"))) {
            if (Files.isRegularFile(candidate) && Files.isExecutable(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    private static String git(Path executable, Path directory, String... arguments) throws Exception {
        java.util.ArrayList<String> command = new java.util.ArrayList<>();
        command.add(executable.toString());
        command.addAll(List.of(arguments));
        Process process =
                new ProcessBuilder(command).directory(directory.toFile()).start();
        byte[] stdout = process.getInputStream().readAllBytes();
        byte[] stderr = process.getErrorStream().readAllBytes();
        if (!process.waitFor(10, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IOException("test Git timed out");
        }
        if (process.exitValue() != 0) {
            throw new IOException(new String(stderr, StandardCharsets.UTF_8));
        }
        return new String(stdout, StandardCharsets.UTF_8);
    }

    private static final class DirectSandbox implements SandboxExecutor {
        @Override
        public SandboxResult execute(SandboxCommand command) throws Exception {
            Instant started = Instant.now();
            ProcessBuilder builder = new ProcessBuilder(command.argv())
                    .directory(command.workingDirectory().toFile());
            builder.environment().clear();
            builder.environment().putAll(command.environment());
            Process process = builder.start();
            byte[] stdout = process.getInputStream().readAllBytes();
            byte[] stderr = process.getErrorStream().readAllBytes();
            boolean completed = process.waitFor(command.policy().timeout().toMillis(), TimeUnit.MILLISECONDS);
            if (!completed) {
                process.destroyForcibly();
            }
            long limit = command.policy().outputLimitBytes();
            boolean truncated = stdout.length + stderr.length > limit;
            return new SandboxResult(
                    completed ? process.exitValue() : -1,
                    bounded(stdout, limit),
                    bounded(stderr, limit),
                    !completed,
                    truncated,
                    Duration.between(started, Instant.now()),
                    "test-direct");
        }

        private static String bounded(byte[] value, long limit) {
            int length = (int) Math.min(value.length, Math.min(limit, Integer.MAX_VALUE));
            return new String(value, 0, length, StandardCharsets.UTF_8);
        }
    }
}
