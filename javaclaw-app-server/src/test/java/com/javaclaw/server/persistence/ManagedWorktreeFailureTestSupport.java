package com.javaclaw.server.persistence;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;

import com.javaclaw.api.AgentProfileRef;
import com.javaclaw.api.AgentTurn;
import com.javaclaw.api.CancellationToken;
import com.javaclaw.api.ConversationThread;
import com.javaclaw.api.CorePayloads;
import com.javaclaw.api.ManagedWorktree;
import com.javaclaw.api.ManagedWorktreeArtifact;
import com.javaclaw.api.MessageRole;
import com.javaclaw.api.PermissionProfile;
import com.javaclaw.api.PermissionProfileRef;
import com.javaclaw.api.ProviderRef;
import com.javaclaw.api.SandboxCommand;
import com.javaclaw.api.SandboxExecutor;
import com.javaclaw.api.SandboxResult;
import com.javaclaw.api.SandboxSession;
import com.javaclaw.api.ThreadExecutionIntent;
import com.javaclaw.api.ThreadId;
import com.javaclaw.api.ToolCatalogSnapshot;
import com.javaclaw.api.TurnBudget;
import com.javaclaw.api.Workspace;
import com.javaclaw.protocol.CanonicalJson;

/** Worktree 失败分支测试共享的真实 Git 与 data-v5 夹具。 */
abstract class ManagedWorktreeFailureTestSupport {
    static final Instant NOW = Instant.parse("2026-09-02T00:00:00Z");
    static final Path GIT = Path.of("/usr/bin/git");

    @TempDir
    Path temporaryDirectory;

    CanonicalJson json;
    Clock clock;
    H2Database database;
    CoreCommandService core;
    AttachmentService attachments;
    ControlledProcessSandbox sandbox;
    ManagedWorktreeService worktrees;
    PermissionProfile standardPermission;
    Path repository;
    Workspace workspace;
    ConversationThread parent;
    AgentTurn parentTurn;

    @BeforeEach
    void createDataV5AndRepository() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(Files.isExecutable(GIT));
        repository = initializeRepository(temporaryDirectory.resolve("repository"));
        json = new CanonicalJson();
        clock = Clock.fixed(NOW, ZoneOffset.UTC);
        database = new H2Database(temporaryDirectory.resolve("data-v5"));
        database.initialize();
        core = new CoreCommandService(database, json, clock);
        PermissionProfileService profiles = new PermissionProfileService(database, json, clock);
        profiles.installStandardProfile();
        standardPermission = profiles.require(PermissionProfileService.STANDARD_PROFILE_ID, 1);
        attachments = new AttachmentService(database, json, clock);
        sandbox = new ControlledProcessSandbox();
        worktrees = new ManagedWorktreeService(database, attachments, json, clock, sandbox);
        workspace = createWorkspace("workspace", repository);
        parent = createRootThread(workspace, "parent");
        parentTurn = startTurn(parent, repository, "parent");
    }

    Workspace createWorkspace(String suffix, Path root) {
        return core.createWorkspace(
                identity("workspace/create", "workspace-" + suffix, Map.of("root", root)), "Workspace " + suffix, root);
    }

    ConversationThread createRootThread(Workspace owner, String suffix) {
        return core.createThread(
                identity("thread/create", "root-" + suffix, Map.of("workspace", owner.id())),
                owner.id(),
                Optional.empty(),
                ThreadExecutionIntent.WORKSPACE,
                "Root " + suffix);
    }

    ConversationThread createChildThread(ConversationThread directParent, ThreadExecutionIntent intent, String suffix) {
        return core.createThread(
                identity("thread/create", "child-" + suffix, Map.of("intent", intent.name())),
                directParent.workspaceId(),
                Optional.of(directParent.id()),
                intent,
                "Child " + suffix);
    }

    AgentTurn startTurn(ConversationThread thread, Path executionRoot, String suffix) {
        ToolCatalogSnapshot tools =
                new ToolCatalogSnapshot(com.javaclaw.api.TurnId.random(), 1, List.of(), standardPermission, NOW);
        return core.startTurn(
                identity("turn/start", "turn-" + suffix, Map.of("thread", thread.id())),
                new TurnStartRequest(
                        thread.id(),
                        new TurnBudget(1_000, 1_000, 10, 4, Duration.ofMinutes(5)),
                        new AgentProfileRef("test-profile", 1),
                        new ProviderRef("test-provider", 1, "test-model"),
                        new PermissionProfileRef(PermissionProfileService.STANDARD_PROFILE_ID, 1),
                        executionRoot,
                        json.encode(Map.of("thread", thread.id())),
                        tools,
                        new CorePayloads.Message(MessageRole.USER, "test", List.of(), Optional.empty()),
                        Optional.empty()));
    }

    WorktreeFixture provision(String suffix) {
        return provision(parent, suffix);
    }

    WorktreeFixture provision(ConversationThread directParent, String suffix) {
        ConversationThread child = createChildThread(directParent, ThreadExecutionIntent.ISOLATED_WRITE, suffix);
        ManagedWorktree created = worktrees.provisionForChild(
                identity("worktree/provision", "provision-" + suffix, child),
                directParent.workspaceId(),
                directParent.id(),
                child.id());
        return new WorktreeFixture(child, created);
    }

    ManagedWorktreeArtifact exportPatch(WorktreeFixture fixture, String suffix) {
        ManagedWorktree current = worktrees.read(fixture.worktree().id());
        return worktrees.exportPatch(identity("worktree/patch/export", "patch-" + suffix, current), current.id());
    }

    ManagedWorktreeArtifact backup(WorktreeFixture fixture, String suffix) {
        ManagedWorktree current = worktrees.read(fixture.worktree().id());
        return worktrees.backup(identity("worktree/backup", "backup-" + suffix, current), current.id());
    }

    void changeTrackedFile(ManagedWorktree worktree, String content) throws Exception {
        Files.writeString(worktree.executionRoot().resolve("tracked.txt"), content, StandardCharsets.UTF_8);
    }

    void advanceWorktreeRevision(ManagedWorktree worktree) throws Exception {
        H2Transactions transactions = new H2Transactions(database);
        ManagedWorktreeRepository repository = new ManagedWorktreeRepository();
        transactions.execute(connection -> {
            ManagedWorktree current = repository.lock(connection, worktree.id());
            repository.transition(connection, current, current.state(), current.backup(), NOW);
            return null;
        });
    }

    void replaceWorktreeParent(ManagedWorktree worktree, ThreadId parentThreadId) throws Exception {
        new H2Transactions(database).execute(connection -> {
            try (java.sql.PreparedStatement statement =
                    connection.prepareStatement("UPDATE CORE.WORKTREE SET PARENT_THREAD_ID = ? WHERE ID = ?")) {
                statement.setString(1, parentThreadId.toString());
                statement.setString(2, worktree.id().toString());
                if (statement.executeUpdate() != 1) {
                    throw new IllegalStateException("测试 Worktree 不存在");
                }
            }
            return null;
        });
    }

    ManagedWorktreeService serviceWithObserver(ManagedWorktreeArtifactService.CleanupIsolationObserver observer) {
        return new ManagedWorktreeService(database, attachments, json, clock, sandbox, observer);
    }

    CommandIdentity identity(String method, String key, Object payload) {
        long expectedRevision = payload instanceof ManagedWorktree worktree ? worktree.revision() : 0;
        return new CommandIdentity(
                method, key, expectedRevision, json.encode(payload).sha256());
    }

    static Path initializeRepository(Path root) throws Exception {
        Files.createDirectories(root);
        git(root, "init");
        git(root, "config", "user.email", "tests@javaclaw.invalid");
        git(root, "config", "user.name", "JavaClaw Tests");
        Files.writeString(root.resolve("tracked.txt"), "base\n", StandardCharsets.UTF_8);
        git(root, "add", "--all");
        git(root, "commit", "-m", "baseline");
        return root;
    }

    static String git(Path directory, String... arguments) throws Exception {
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

    record WorktreeFixture(ConversationThread child, ManagedWorktree worktree) {}

    @FunctionalInterface
    interface CommandHook {
        void accept(SandboxCommand command) throws Exception;
    }

    static final class ControlledProcessSandbox implements SandboxExecutor {
        private String rejectedCommand;
        private CommandHook before = command -> {};
        private CommandHook after = command -> {};

        void reject(String commandId) {
            rejectedCommand = commandId;
        }

        void before(CommandHook hook) {
            before = hook;
        }

        void after(CommandHook hook) {
            after = hook;
        }

        void clearControls() {
            rejectedCommand = null;
            before = command -> {};
            after = command -> {};
        }

        @Override
        public SandboxResult execute(
                SandboxCommand command, PermissionProfile permission, CancellationToken cancellation) throws Exception {
            cancellation.throwIfCancelled();
            long started = System.nanoTime();
            before.accept(command);
            if (command.id().equals(rejectedCommand)) {
                return new SandboxResult(
                        1,
                        new byte[0],
                        "injected failure".getBytes(StandardCharsets.UTF_8),
                        false,
                        false,
                        Duration.ofNanos(System.nanoTime() - started));
            }
            SandboxResult result = run(command, cancellation, started);
            if (result.exitCode() == 0) {
                after.accept(command);
            }
            return result;
        }

        private SandboxResult run(SandboxCommand command, CancellationToken cancellation, long started)
                throws Exception {
            ProcessBuilder builder = new ProcessBuilder(command.argv());
            builder.directory(command.workingDirectory().toFile());
            builder.environment().clear();
            builder.environment().putAll(command.environment());
            Process process = builder.start();
            try (OutputStream input = process.getOutputStream()) {
                input.write(command.standardInput());
            }
            byte[] output = process.getInputStream().readAllBytes();
            byte[] error = process.getErrorStream().readAllBytes();
            int exit = process.waitFor();
            cancellation.throwIfCancelled();
            return new SandboxResult(exit, output, error, false, false, Duration.ofNanos(System.nanoTime() - started));
        }

        @Override
        public SandboxSession open(
                SandboxCommand command, PermissionProfile permission, CancellationToken cancellation) {
            throw new UnsupportedOperationException("测试只覆盖批处理 Git 操作");
        }
    }
}
