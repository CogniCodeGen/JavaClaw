package com.javaclaw.server.persistence;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.javaclaw.api.ApprovalRequirement;
import com.javaclaw.api.CancellationSource;
import com.javaclaw.api.FilePermission;
import com.javaclaw.api.NetworkPermission;
import com.javaclaw.api.PermissionProfile;
import com.javaclaw.api.ProcessPermission;
import com.javaclaw.api.ResourceLimits;
import com.javaclaw.api.SandboxCommand;
import com.javaclaw.api.SandboxExecutor;
import com.javaclaw.api.SandboxMode;
import com.javaclaw.api.SandboxResult;
import com.javaclaw.api.ToolPermission;
import com.javaclaw.api.ToolRisk;

/** 使用隔离临时 index 执行 Managed Worktree 的固定 Git 操作。 */
final class ManagedGitWorktreeRunner {
    static final int MAX_PATCH_BYTES = 16 * 1024 * 1024;
    private static final Duration TIMEOUT = Duration.ofMinutes(2);

    private final SandboxExecutor sandbox;
    private final Path git;

    ManagedGitWorktreeRunner(SandboxExecutor sandbox) {
        this.sandbox = java.util.Objects.requireNonNull(sandbox, "sandbox");
        git = ManagedGitEnvironment.executable();
    }

    String baseCommit(Path workspace, Path managedRoot) {
        byte[] output = execute(
                "worktree-base", arguments("rev-parse", "--verify", "HEAD"), workspace, managedRoot, new byte[0]);
        String commit = new String(output, StandardCharsets.US_ASCII).strip().toLowerCase(java.util.Locale.ROOT);
        if (!commit.matches("(?:[0-9a-f]{40}|[0-9a-f]{64})")) {
            throw new PersistenceException("Git HEAD 未解析为完整 commit");
        }
        return commit;
    }

    void create(Path workspace, Path destination, String baseCommit) {
        List<String> command =
                arguments("worktree", "add", "--detach", destination.toString(), safeObjectId(baseCommit));
        execute("worktree-create", command, workspace, destination.getParent(), new byte[0]);
    }

    boolean isRegistered(Path workspace, Path destination) {
        byte[] output = execute(
                "worktree-list",
                arguments("worktree", "list", "--porcelain"),
                workspace,
                destination.getParent(),
                new byte[0]);
        Path normalized = destination.toAbsolutePath().normalize();
        return new String(output, StandardCharsets.UTF_8)
                .lines()
                .filter(line -> line.startsWith("worktree "))
                .map(line -> Path.of(line.substring("worktree ".length()))
                        .toAbsolutePath()
                        .normalize())
                .anyMatch(normalized::equals);
    }

    byte[] patch(Path workspace, Path executionRoot, Path scratchDirectory, String baseCommit) {
        Path scratch = prepareScratch(scratchDirectory);
        Path temporaryIndex = scratch.resolve("index").normalize();
        requireChild(scratch, temporaryIndex);
        deleteTemporaryIndex(temporaryIndex);
        Map<String, String> environment = Map.of("GIT_INDEX_FILE", temporaryIndex.toString());
        try {
            execute(
                    "worktree-index-read",
                    arguments("read-tree", safeObjectId(baseCommit)),
                    executionRoot,
                    scratch,
                    new byte[0],
                    environment);
            byte[] untracked = execute(
                    "worktree-untracked",
                    arguments("ls-files", "--others", "--exclude-standard", "-z"),
                    executionRoot,
                    scratch,
                    new byte[0],
                    environment);
            if (untracked.length > 0) {
                execute(
                        "worktree-intent-to-add",
                        arguments("add", "--intent-to-add", "--pathspec-from-file=-", "--pathspec-file-nul"),
                        executionRoot,
                        scratch,
                        untracked,
                        environment);
            }
            byte[] patch = execute(
                    "worktree-patch",
                    arguments(
                            "diff",
                            "--binary",
                            "--full-index",
                            "--find-renames",
                            "--no-ext-diff",
                            "--no-textconv",
                            safeObjectId(baseCommit),
                            "--"),
                    executionRoot,
                    scratch,
                    new byte[0],
                    environment);
            if (patch.length > MAX_PATCH_BYTES) {
                throw PersistenceException.invalidRequest("Managed Worktree Patch 超过 16 MiB 上限");
            }
            return patch;
        } finally {
            deleteTemporaryIndex(temporaryIndex);
            deleteTemporaryIndex(temporaryIndex.resolveSibling("index.lock"));
        }
    }

    void apply(Path workspace, Path managedRoot, byte[] patch) {
        byte[] owned = java.util.Objects.requireNonNull(patch, "patch").clone();
        String indexBefore = indexDigest(workspace, managedRoot);
        try {
            execute(
                    "worktree-apply-check",
                    arguments("apply", "--check", "--whitespace=nowarn", "-"),
                    workspace,
                    managedRoot,
                    owned);
        } catch (PersistenceException failure) {
            throw new ApplyRejectedException(failure);
        }
        execute("worktree-apply", arguments("apply", "--whitespace=nowarn", "-"), workspace, managedRoot, owned);
        String indexAfter = indexDigest(workspace, managedRoot);
        if (!indexBefore.equals(indexAfter)) {
            throw new PersistenceException("worktree_apply 不得修改用户真实 Git index");
        }
    }

    /** {@code git apply --check} 在任何写入前拒绝 Patch。 */
    static final class ApplyRejectedException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        ApplyRejectedException(PersistenceException cause) {
            super("Git 拒绝应用 Patch", cause);
        }
    }

    void cleanup(Path workspace, Path destination) {
        execute(
                "worktree-cleanup",
                arguments("worktree", "remove", "--force", destination.toString()),
                workspace,
                destination.getParent(),
                new byte[0]);
    }

    void move(Path workspace, Path source, Path destination) {
        Path managedRoot = ManagedGitEnvironment.commonManagedRoot(source, destination);
        execute(
                "worktree-isolate",
                arguments("worktree", "move", "--force", source.toString(), destination.toString()),
                workspace,
                managedRoot,
                new byte[0]);
    }

    private byte[] execute(
            String id, List<String> arguments, Path workingDirectory, Path managedRoot, byte[] standardInput) {
        return execute(id, arguments, workingDirectory, managedRoot, standardInput, Map.of());
    }

    private byte[] execute(
            String id,
            List<String> arguments,
            Path workingDirectory,
            Path managedRoot,
            byte[] standardInput,
            Map<String, String> additions) {
        if (!Files.isExecutable(git)) {
            throw new PersistenceException("Git executable is unavailable: " + git);
        }
        Map<String, String> environment = new java.util.HashMap<>(baseEnvironment(managedRoot));
        environment.putAll(additions);
        SandboxCommand command = new SandboxCommand(
                id, arguments, workingDirectory, Map.copyOf(environment), standardInput, SandboxMode.BATCH, TIMEOUT);
        try {
            SandboxResult result =
                    sandbox.execute(command, permission(workingDirectory, managedRoot), new CancellationSource());
            if (result.exitCode() != 0 || result.timedOut() || result.cancelled()) {
                String error = new String(result.standardError(), StandardCharsets.UTF_8).strip();
                throw PersistenceException.invalidRequest("Git Managed Worktree 操作被拒绝: " + bounded(error));
            }
            return result.standardOutput();
        } catch (PersistenceException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new PersistenceException("Git Managed Worktree Sandbox 执行失败", failure);
        }
    }

    private ArrayList<String> arguments(String... values) {
        ArrayList<String> result = new ArrayList<>();
        result.add(git.toString());
        result.addAll(List.of(values));
        return result;
    }

    private Map<String, String> baseEnvironment(Path managedRoot) {
        return ManagedGitEnvironment.variables(git, managedRoot);
    }

    private PermissionProfile permission(Path workingDirectory, Path managedRoot) {
        return new PermissionProfile(
                "core-managed-worktree",
                1,
                new FilePermission(
                        List.of(workingDirectory, managedRoot), List.of(workingDirectory, managedRoot), true, false),
                new NetworkPermission(Set.of(), Set.of(), true),
                new ProcessPermission(Set.of(git.getFileName().toString()), false, TIMEOUT),
                new ToolPermission(Set.of(), ToolRisk.READ_ONLY, ApprovalRequirement.NONE),
                new ResourceLimits(512L * 1024 * 1024, (long) MAX_PATCH_BYTES + 1, 8, 128));
    }

    private String indexDigest(Path workspace, Path managedRoot) {
        byte[] pathBytes = execute(
                "worktree-index-path",
                arguments("rev-parse", "--git-path", "index"),
                workspace,
                managedRoot,
                new byte[0]);
        Path reported = Path.of(new String(pathBytes, StandardCharsets.UTF_8).strip());
        Path index = reported.isAbsolute()
                ? reported.normalize()
                : workspace.resolve(reported).normalize();
        try {
            return Files.exists(index, LinkOption.NOFOLLOW_LINKS) ? digest(index) : "missing";
        } catch (IOException failure) {
            throw new PersistenceException("无法校验用户 Git index", failure);
        }
    }

    private static String digest(Path file) throws IOException {
        MessageDigest digest = sha256();
        byte[] buffer = new byte[8192];
        try (InputStream input = Files.newInputStream(file)) {
            int count;
            while ((count = input.read(buffer)) >= 0) {
                if (count > 0) {
                    digest.update(buffer, 0, count);
                }
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 is unavailable", failure);
        }
    }

    private static Path prepareScratch(Path directory) {
        Path scratch = directory.toAbsolutePath().normalize();
        try {
            Files.createDirectories(scratch);
            if (Files.isSymbolicLink(scratch) || !Files.isDirectory(scratch, LinkOption.NOFOLLOW_LINKS)) {
                throw new PersistenceException("Worktree 临时目录不安全");
            }
            return scratch;
        } catch (IOException failure) {
            throw new PersistenceException("无法准备 Worktree 临时目录", failure);
        }
    }

    private static void requireChild(Path parent, Path child) {
        if (!child.startsWith(parent) || child.equals(parent)) {
            throw new PersistenceException("Worktree 临时文件路径越界");
        }
    }

    private static void deleteTemporaryIndex(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException failure) {
            throw new PersistenceException("无法清理 Worktree 临时 index", failure);
        }
    }

    private static String safeObjectId(String value) {
        String normalized =
                java.util.Objects.requireNonNull(value, "baseCommit").strip().toLowerCase(java.util.Locale.ROOT);
        if (!normalized.matches("(?:[0-9a-f]{40}|[0-9a-f]{64})")) {
            throw PersistenceException.invalidRequest("baseCommit 不是完整 Git object id");
        }
        return normalized;
    }

    private static String bounded(String error) {
        if (error.isEmpty()) {
            return "no diagnostic output";
        }
        return error.length() <= 1_000 ? error : error.substring(0, 1_000);
    }
}
