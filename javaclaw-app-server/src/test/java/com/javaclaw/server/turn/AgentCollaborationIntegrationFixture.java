package com.javaclaw.server.turn;

import java.io.IOException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;

import com.javaclaw.api.AgentTurn;
import com.javaclaw.api.CancellationToken;
import com.javaclaw.api.ConversationThread;
import com.javaclaw.api.ExecutionOverrides;
import com.javaclaw.api.FilePermission;
import com.javaclaw.api.PermissionConstraint;
import com.javaclaw.api.PermissionProfile;
import com.javaclaw.api.SandboxCommand;
import com.javaclaw.api.SandboxExecutor;
import com.javaclaw.api.SandboxResult;
import com.javaclaw.api.SandboxSession;
import com.javaclaw.api.ThreadExecutionIntent;
import com.javaclaw.protocol.CollaborationRpcContracts;
import com.javaclaw.runtime.BudgetAccount;
import com.javaclaw.runtime.ModelFinishReason;
import com.javaclaw.runtime.ModelInvocationResult;
import com.javaclaw.runtime.ModelToolCall;
import com.javaclaw.runtime.ModelUsage;
import com.javaclaw.runtime.ToolCatalogPort;
import com.javaclaw.runtime.TurnExecutionCommand;
import com.javaclaw.runtime.TurnExecutionResult;
import com.javaclaw.server.extension.contract.ExtensionHost;
import com.javaclaw.server.persistence.ApprovalService;
import com.javaclaw.server.persistence.ChildThreadReservation;
import com.javaclaw.server.persistence.CommandIdentity;
import com.javaclaw.server.persistence.ExtensionCatalogRepository;
import com.javaclaw.server.persistence.H2Database;
import com.javaclaw.server.security.grant.UnattendedToolGrantService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** 真实 H2、工具目录治理与临时 Git 的协作夹具；仅模型和操作系统隔离边界由可控实现替换。 */
abstract class AgentCollaborationIntegrationFixture extends AgentCollaborationTestFixture {
    final GitSandbox gitSandbox = new GitSandbox();
    final AtomicInteger childExecutions = new AtomicInteger();
    final AtomicReference<TurnExecutionCommand> childCommand = new AtomicReference<>();
    ExtensionToolPlatform tools;
    ApprovalService approvals;

    @Override
    FilePermission initialFilePermission(PermissionProfile source) {
        // 文件授权必须在 Turn 冻结的 revision 2 就存在，后续 revision 只能验证收窄。
        Path root = directory.resolve("workspace").toAbsolutePath().normalize();
        return new FilePermission(List.of(root), List.of(root), false, false);
    }

    @Override
    SandboxExecutor sandbox() {
        return gitSandbox;
    }

    @Override
    ToolCatalogPort catalog(H2Database database) {
        approvals = new ApprovalService(database, json, CLOCK);
        tools = new ExtensionToolPlatform(new ExtensionToolPlatform.Dependencies(
                emptyExtensions(),
                core,
                approvals,
                permissions,
                worktrees,
                new ExtensionCatalogRepository(database, json, CLOCK),
                json,
                CLOCK,
                new UnattendedToolGrantService(database, json, CLOCK),
                Optional.empty()));
        return tools;
    }

    @BeforeEach
    void bindCollaborationAndCreateGitRepository() throws Exception {
        tools.bindCollaboration(service);
        assumeTrue(Files.isExecutable(Path.of("/usr/bin/git")), "临时 Worktree 测试需要 Git");
        Path root = workspaceRoot();
        git(root, "init");
        Files.createDirectories(root.resolve("allowed"));
        Files.writeString(root.resolve("allowed/tracked.txt"), "base\n", StandardCharsets.UTF_8);
        git(root, "add", "--all");
        git(
                root,
                "-c",
                "user.name=JavaClaw Tests",
                "-c",
                "user.email=tests@javaclaw.invalid",
                "commit",
                "-m",
                "baseline");
    }

    @AfterEach
    void closeApprovalService() throws Exception {
        if (approvals != null) {
            approvals.close();
        }
    }

    @Override
    TurnExecutionResult runChild(TurnExecutionCommand command, CancellationToken cancellation) throws Exception {
        childCommand.set(command);
        childExecutions.incrementAndGet();
        return super.runChild(command, cancellation);
    }

    Path workspaceRoot() {
        return core.findWorkspace(workspace).orElseThrow().root();
    }

    BudgetAccount activateToolBoundary(AgentTurn turn) {
        BudgetAccount budget = new BudgetAccount(turn.budget(), CLOCK, turn.createdAt(), ModelUsage.zero(), 0);
        journal.activateBudget(turn.id(), budget);
        journal.recordModelIntent(turn.id(), 1, "b".repeat(64));
        journal.commitModelResult(
                turn.id(),
                1,
                new ModelInvocationResult(
                        "",
                        List.of(new ModelToolCall(
                                "nested-spawn",
                                CollaborationTools.all().getFirst().identity(),
                                json.parse("{}"))),
                        ModelUsage.zero(),
                        Optional.empty(),
                        Optional.empty(),
                        ModelFinishReason.TOOL_CALLS),
                ModelUsage.zero());
        return budget;
    }

    ConversationThread reserveOnly(String key, CollaborationRpcContracts.SpawnPayload request) {
        ExecutionOverrides requested = request.execution();
        ExecutionOverrides selected = new ExecutionOverrides(
                Optional.of(roles.requireLatest(request.agentType()).ref()),
                requested.provider(),
                requested.permissionProfile(),
                requested.approvalPolicy(),
                requested.budget(),
                requested.visibleCapabilities(),
                requested.reasoning());
        var snapshot = dispatcher.freezeChild(parent, selected);
        ThreadExecutionIntent intent = snapshot.configuration().permissionConstraint() == PermissionConstraint.READ_ONLY
                ? ThreadExecutionIntent.READ_ONLY
                : ThreadExecutionIntent.ISOLATED_WRITE;
        return children.reserve(
                new CommandIdentity(
                        "agent/spawn/thread",
                        key + ":thread",
                        parent.revision(),
                        json.encode(request).sha256()),
                new ChildThreadReservation(
                        parent.id(), snapshot.configuration(), snapshot.toolCatalog(), intent, request.title()));
    }

    void updateParentPermission(PermissionProfile updated) {
        permissions.update(
                identity(
                        "permissionProfile/update",
                        "parent-permission-" + updated.version(),
                        updated.version() - 1,
                        updated),
                updated);
    }

    private static ExtensionHost emptyExtensions() {
        return (ExtensionHost) Proxy.newProxyInstance(
                AgentCollaborationIntegrationFixture.class.getClassLoader(),
                new Class<?>[] {ExtensionHost.class},
                (proxy, method, arguments) -> {
                    if (List.of("tools", "list", "views").contains(method.getName())) {
                        return List.of();
                    }
                    throw new AssertionError("协作集成测试不能调用外部扩展: " + method.getName());
                });
    }

    private static void git(Path root, String... arguments) throws Exception {
        List<String> command = new ArrayList<>(List.of("/usr/bin/git"));
        command.addAll(List.of(arguments));
        ProcessBuilder builder =
                new ProcessBuilder(command).directory(root.toFile()).redirectErrorStream(true);
        builder.environment().put("GIT_CONFIG_NOSYSTEM", "1");
        builder.environment().put("GIT_CONFIG_GLOBAL", "/dev/null");
        Process process = builder.start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, process.waitFor(), output);
    }

    /** 在临时仓库执行真实 Git，并能模拟目录创建成功但服务尚未提交 H2 的故障窗口。 */
    static final class GitSandbox implements SandboxExecutor {
        final AtomicInteger creations = new AtomicInteger();
        final AtomicBoolean failAfterCreate = new AtomicBoolean();

        @Override
        public SandboxResult execute(
                SandboxCommand command, PermissionProfile permission, CancellationToken cancellation) throws Exception {
            cancellation.throwIfCancelled();
            long started = System.nanoTime();
            ProcessBuilder builder = new ProcessBuilder(command.argv());
            builder.directory(command.workingDirectory().toFile());
            builder.environment().clear();
            builder.environment().putAll(command.environment());
            Process process = builder.start();
            try (var input = process.getOutputStream()) {
                input.write(command.standardInput());
            }
            byte[] output = process.getInputStream().readAllBytes();
            byte[] error = process.getErrorStream().readAllBytes();
            int exit = process.waitFor();
            cancellation.throwIfCancelled();
            if (exit == 0 && command.id().equals("worktree-create")) {
                creations.incrementAndGet();
                if (failAfterCreate.compareAndSet(true, false)) {
                    throw new IOException("模拟 Git 创建完成后、H2 提交前中断");
                }
            }
            return new SandboxResult(exit, output, error, false, false, Duration.ofNanos(System.nanoTime() - started));
        }

        @Override
        public SandboxSession open(
                SandboxCommand command, PermissionProfile permission, CancellationToken cancellation) {
            throw new AssertionError("协作集成测试仅使用批处理 Git");
        }
    }
}
