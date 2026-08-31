package com.javaclaw.agent.runtime;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.javaclaw.core.api.AgentThread;
import com.javaclaw.core.api.ApprovalPolicy;
import com.javaclaw.core.api.ThreadId;
import com.javaclaw.core.api.ThreadStatus;
import com.javaclaw.core.api.TurnConfig;
import com.javaclaw.sandbox.api.SandboxPolicy;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class WorkspaceWriteCoordinatorTest {
    @TempDir
    Path temporary;

    @Test
    void permitsOnlyOneWriterAcrossANonGitWorkspace() throws Exception {
        Path rootDirectory = Files.createDirectories(temporary.resolve("plain"));
        Path childDirectory = Files.createDirectories(temporary.resolve("plain-child"));
        AgentThread root = thread("root", null, rootDirectory);
        AgentThread first = thread("first", root.id(), rootDirectory);
        AgentThread second = thread("second", root.id(), childDirectory);
        Map<ThreadId, AgentThread> threads = Map.of(root.id(), root, first.id(), first, second.id(), second);
        WorkspaceWriteCoordinator coordinator = new WorkspaceWriteCoordinator();

        try (var ignored = coordinator.acquire(first, writable(first), id -> Optional.ofNullable(threads.get(id)))) {
            assertThrows(
                    IllegalStateException.class,
                    () -> coordinator.acquire(second, writable(second), id -> Optional.ofNullable(threads.get(id))));
        }
        assertDoesNotThrow(() -> coordinator
                .acquire(second, writable(second), id -> Optional.ofNullable(threads.get(id)))
                .close());
    }

    @Test
    void permitsParallelWritersOnlyInDistinctLinkedWorktrees() throws Exception {
        Path rootDirectory = Files.createDirectories(temporary.resolve("repo"));
        Path commonGit = Files.createDirectories(rootDirectory.resolve(".git"));
        Path firstDirectory = linkedWorktree(rootDirectory, commonGit, "first");
        Path secondDirectory = linkedWorktree(rootDirectory, commonGit, "second");
        AgentThread root = thread("root", null, rootDirectory);
        AgentThread first = thread("first", root.id(), firstDirectory);
        AgentThread second = thread("second", root.id(), secondDirectory);
        AgentThread duplicatePath = thread("duplicate", root.id(), firstDirectory);
        AgentThread unsafe = thread("unsafe", root.id(), rootDirectory);
        Map<ThreadId, AgentThread> threads = Map.of(
                root.id(),
                root,
                first.id(),
                first,
                second.id(),
                second,
                duplicatePath.id(),
                duplicatePath,
                unsafe.id(),
                unsafe);
        WorkspaceWriteCoordinator coordinator = new WorkspaceWriteCoordinator();

        try (var firstLease = coordinator.acquire(first, writable(first), id -> Optional.ofNullable(threads.get(id)));
                var secondLease =
                        coordinator.acquire(second, writable(second), id -> Optional.ofNullable(threads.get(id)))) {
            assertThrows(
                    IllegalStateException.class,
                    () -> coordinator.acquire(
                            duplicatePath, writable(duplicatePath), id -> Optional.ofNullable(threads.get(id))));
            assertThrows(
                    IllegalStateException.class,
                    () -> coordinator.acquire(unsafe, writable(unsafe), id -> Optional.ofNullable(threads.get(id))));
        }
    }

    @Test
    void rejectsWritableRootsThatEscapeTheThreadDirectory() throws Exception {
        Path cwd = Files.createDirectories(temporary.resolve("workspace"));
        Path outside = Files.createDirectories(temporary.resolve("outside"));
        AgentThread thread = thread("root", null, cwd);
        SandboxPolicy escaping = SandboxPolicy.workspaceWrite(
                Set.of(cwd, outside), Set.of(outside), Set.of(cwd.resolve(".git"), cwd.resolve(".javaclaw")));
        TurnConfig config = new TurnConfig(
                "model", "provider", "medium", cwd, escaping, ApprovalPolicy.ON_RISK, Set.of(), Map.of());

        assertThrows(
                IllegalArgumentException.class,
                () -> new WorkspaceWriteCoordinator().acquire(thread, config, ignored -> Optional.empty()));
    }

    private Path linkedWorktree(Path root, Path commonGit, String name) throws Exception {
        Path metadata = Files.createDirectories(commonGit.resolve("worktrees").resolve(name));
        Files.writeString(metadata.resolve("commondir"), "../..\n");
        Path directory = Files.createDirectories(temporary.resolve("worktree-" + name));
        Files.writeString(directory.resolve(".git"), "gitdir: " + metadata + "\n");
        return directory;
    }

    private static AgentThread thread(String id, ThreadId parent, Path cwd) {
        return new AgentThread(
                new ThreadId(id),
                "workspace",
                parent,
                null,
                id,
                cwd,
                ThreadStatus.ACTIVE,
                0,
                0,
                1,
                Instant.EPOCH,
                Instant.EPOCH);
    }

    private static TurnConfig writable(AgentThread thread) {
        Path cwd = thread.workingDirectory();
        SandboxPolicy policy = SandboxPolicy.workspaceWrite(
                Set.of(cwd), Set.of(cwd), Set.of(cwd.resolve(".git"), cwd.resolve(".javaclaw")));
        return new TurnConfig("model", "provider", "medium", cwd, policy, ApprovalPolicy.ON_RISK, Set.of(), Map.of());
    }
}
