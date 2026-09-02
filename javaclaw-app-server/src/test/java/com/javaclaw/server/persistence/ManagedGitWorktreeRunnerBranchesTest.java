package com.javaclaw.server.persistence;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.javaclaw.api.CancellationToken;
import com.javaclaw.api.PermissionProfile;
import com.javaclaw.api.SandboxCommand;
import com.javaclaw.api.SandboxExecutor;
import com.javaclaw.api.SandboxResult;
import com.javaclaw.api.SandboxSession;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ManagedGitWorktreeRunnerBranchesTest {
    private static final byte[] EMPTY = new byte[0];
    private static final String COMMIT = "a".repeat(40);

    @TempDir
    Path temporaryDirectory;

    private RecordingSandbox sandbox;
    private ManagedGitWorktreeRunner git;
    private Path workspace;
    private Path managedRoot;

    @BeforeEach
    void initializeRunner() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(Files.isExecutable(ManagedGitEnvironment.executable()));
        sandbox = new RecordingSandbox();
        git = new ManagedGitWorktreeRunner(sandbox);
        workspace = Files.createDirectories(temporaryDirectory.resolve("workspace"));
        managedRoot = Files.createDirectories(temporaryDirectory.resolve("managed"));
    }

    @Test
    void baseCommit与注册列表只接受规范Git输出() {
        sandbox.behavior = command -> switch (command.id()) {
            case "worktree-base" -> success((COMMIT.toUpperCase() + "\n").getBytes(StandardCharsets.US_ASCII));
            case "worktree-list" -> success(("worktree " + managedRoot + "\n").getBytes(StandardCharsets.UTF_8));
            default -> success(EMPTY);
        };

        assertEquals(COMMIT, git.baseCommit(workspace, managedRoot));
        assertTrue(git.isRegistered(workspace, managedRoot));
        sandbox.behavior = command -> success("worktree /different\n".getBytes(StandardCharsets.UTF_8));
        assertFalse(git.isRegistered(workspace, managedRoot));
        sandbox.behavior = command -> success("short\n".getBytes(StandardCharsets.US_ASCII));
        assertThrows(PersistenceException.class, () -> git.baseCommit(workspace, managedRoot));
    }

    @Test
    void patch按未跟踪文件决定IntentToAdd并限制输出大小() throws Exception {
        Path executionRoot = Files.createDirectories(workspace.resolve("child"));
        Path scratch = managedRoot.resolve("scratch");
        sandbox.behavior = command -> switch (command.id()) {
            case "worktree-untracked" -> success(EMPTY);
            case "worktree-patch" -> success("patch".getBytes(StandardCharsets.UTF_8));
            default -> success(EMPTY);
        };

        assertArrayEquals(
                "patch".getBytes(StandardCharsets.UTF_8), git.patch(workspace, executionRoot, scratch, COMMIT));
        assertFalse(sandbox.commandIds.contains("worktree-intent-to-add"));

        sandbox.commandIds.clear();
        sandbox.behavior = command -> switch (command.id()) {
            case "worktree-untracked" -> success("new.txt\0".getBytes(StandardCharsets.UTF_8));
            case "worktree-patch" -> success("patch".getBytes(StandardCharsets.UTF_8));
            default -> success(EMPTY);
        };
        git.patch(workspace, executionRoot, scratch, COMMIT);
        assertTrue(sandbox.commandIds.contains("worktree-intent-to-add"));

        sandbox.behavior = command -> command.id().equals("worktree-patch")
                ? success(new byte[ManagedGitWorktreeRunner.MAX_PATCH_BYTES + 1])
                : success(EMPTY);
        assertThrows(PersistenceException.class, () -> git.patch(workspace, executionRoot, scratch, COMMIT));
    }

    @Test
    void patch拒绝不安全Scratch与非法完整对象Id() throws Exception {
        Path executionRoot = Files.createDirectories(workspace.resolve("child"));
        Path outside = Files.createDirectories(temporaryDirectory.resolve("outside"));
        Path linked = managedRoot.resolve("linked-scratch");
        Files.createSymbolicLink(linked, outside);

        assertThrows(PersistenceException.class, () -> git.patch(workspace, executionRoot, linked, COMMIT));
        assertThrows(
                PersistenceException.class,
                () -> git.patch(workspace, executionRoot, managedRoot.resolve("scratch"), "ambiguous"));
    }

    @Test
    void sandbox失败分别保留退出超时取消与异常语义() {
        sandbox.behavior = command -> new SandboxResult(1, EMPTY, EMPTY, false, false, Duration.ZERO);
        assertThrows(PersistenceException.class, () -> git.baseCommit(workspace, managedRoot));

        byte[] longError = "x".repeat(1_100).getBytes(StandardCharsets.UTF_8);
        sandbox.behavior = command -> new SandboxResult(0, EMPTY, longError, true, false, Duration.ZERO);
        PersistenceException timedOut =
                assertThrows(PersistenceException.class, () -> git.baseCommit(workspace, managedRoot));
        assertTrue(timedOut.getMessage().length() < 1_100);

        sandbox.behavior = command -> new SandboxResult(0, EMPTY, EMPTY, false, true, Duration.ZERO);
        assertThrows(PersistenceException.class, () -> git.baseCommit(workspace, managedRoot));
        sandbox.failure = new IOException("sandbox unavailable");
        assertThrows(PersistenceException.class, () -> git.baseCommit(workspace, managedRoot));
    }

    @Test
    void apply校验真实Index未变化并区分相对绝对与缺失路径() throws Exception {
        Path gitDirectory = Files.createDirectories(workspace.resolve(".git"));
        Path index = Files.writeString(gitDirectory.resolve("index"), "before", StandardCharsets.UTF_8);
        sandbox.behavior = command -> command.id().equals("worktree-index-path")
                ? success(".git/index\n".getBytes(StandardCharsets.UTF_8))
                : success(EMPTY);
        git.apply(workspace, managedRoot, "patch".getBytes(StandardCharsets.UTF_8));

        sandbox.behavior = command -> command.id().equals("worktree-index-path")
                ? success((index.toAbsolutePath() + "\n").getBytes(StandardCharsets.UTF_8))
                : success(EMPTY);
        git.apply(workspace, managedRoot, "patch".getBytes(StandardCharsets.UTF_8));

        sandbox.behavior = command -> command.id().equals("worktree-index-path")
                ? success(".git/missing-index\n".getBytes(StandardCharsets.UTF_8))
                : success(EMPTY);
        git.apply(workspace, managedRoot, "patch".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void apply在预检失败或用户Index变化时失败关闭() throws Exception {
        Path gitDirectory = Files.createDirectories(workspace.resolve(".git"));
        Path index = Files.writeString(gitDirectory.resolve("index"), "before", StandardCharsets.UTF_8);
        sandbox.behavior = command -> command.id().equals("worktree-apply-check")
                ? new SandboxResult(1, EMPTY, "rejected".getBytes(StandardCharsets.UTF_8), false, false, Duration.ZERO)
                : success(".git/index\n".getBytes(StandardCharsets.UTF_8));
        assertThrows(
                ManagedGitWorktreeRunner.ApplyRejectedException.class,
                () -> git.apply(workspace, managedRoot, "patch".getBytes(StandardCharsets.UTF_8)));

        sandbox.behavior = command -> {
            if (command.id().equals("worktree-index-path")) {
                return success(".git/index\n".getBytes(StandardCharsets.UTF_8));
            }
            if (command.id().equals("worktree-apply")) {
                try {
                    Files.writeString(index, "after", StandardCharsets.UTF_8);
                } catch (IOException failure) {
                    throw new AssertionError(failure);
                }
            }
            return success(EMPTY);
        };
        assertThrows(PersistenceException.class, () -> git.apply(workspace, managedRoot, EMPTY));
    }

    private static SandboxResult success(byte[] output) {
        return new SandboxResult(0, output, EMPTY, false, false, Duration.ZERO);
    }

    private static final class RecordingSandbox implements SandboxExecutor {
        private final List<String> commandIds = new ArrayList<>();
        private Function<SandboxCommand, SandboxResult> behavior = command -> success(EMPTY);
        private Exception failure;

        @Override
        public SandboxResult execute(
                SandboxCommand command, PermissionProfile permission, CancellationToken cancellation) throws Exception {
            commandIds.add(command.id());
            if (failure != null) {
                throw failure;
            }
            return behavior.apply(command);
        }

        @Override
        public SandboxSession open(
                SandboxCommand command, PermissionProfile permission, CancellationToken cancellation) {
            throw new UnsupportedOperationException("测试只覆盖批处理 Git 命令");
        }
    }
}
