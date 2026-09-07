package com.javaclaw.server.coding;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import com.javaclaw.api.AgentTurn;
import com.javaclaw.api.CancellationSource;
import com.javaclaw.api.ConversationThread;
import com.javaclaw.api.CorePayloads;
import com.javaclaw.api.FilePermission;
import com.javaclaw.api.MessageRole;
import com.javaclaw.api.PermissionProfile;
import com.javaclaw.api.PermissionProfileRef;
import com.javaclaw.api.ThreadExecutionIntent;
import com.javaclaw.api.ToolCatalogSnapshot;
import com.javaclaw.api.ToolDescriptor;
import com.javaclaw.api.ToolIdentity;
import com.javaclaw.api.ToolRisk;
import com.javaclaw.api.TurnId;
import com.javaclaw.api.TurnStatus;
import com.javaclaw.api.Workspace;
import com.javaclaw.builtin.contracts.CodingContracts;
import com.javaclaw.builtin.contracts.CodingResults;
import com.javaclaw.nativehost.sandbox.PlatformSandboxExecutor;
import com.javaclaw.server.TurnContractFixtures;
import com.javaclaw.server.persistence.AttachmentService;
import com.javaclaw.server.persistence.CommandIdentity;
import com.javaclaw.server.persistence.ManagedWorktreeService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 在当前 macOS Runner 上同时验证真实 Git 工作树、H2 绑定和原生文件 Worker。 */
@EnabledOnOs(OS.MAC)
class CodingManagedWorktreeIntegrationTest {
    @TempDir
    Path temporary;

    @Test
    void 真实隔离子任务能读取所属工作树而同目录伪装普通Workspace仍被拒绝() throws Exception {
        try (var fixture = new CodingTestFixture(temporary)) {
            seedRepository(fixture.root);
            var child = fixture.core.createThread(
                    fixture.identity("thread/create", "isolated-child", Map.of()),
                    fixture.workspace.id(),
                    Optional.of(fixture.turn.threadId()),
                    ThreadExecutionIntent.ISOLATED_WRITE,
                    "Isolated child");
            var worktrees = new ManagedWorktreeService(
                    fixture.database,
                    new AttachmentService(fixture.database, fixture.json, fixture.clock),
                    fixture.json,
                    fixture.clock,
                    new PlatformSandboxExecutor());
            var worktree = worktrees.provisionForChild(
                    fixture.identity("worktree/provision", "provision", child),
                    fixture.workspace.id(),
                    fixture.turn.threadId(),
                    child.id());
            assertTrue(worktree.executionRoot()
                    .startsWith(fixture.database.dataRoot().toRealPath().resolve("worktrees")));
            PermissionProfile isolatedPermission = fixture.profiles.resolveForExecution(
                    fixture.permission.id(),
                    fixture.permission.version(),
                    fixture.workspace,
                    worktree.executionRoot(),
                    true);
            var turn = start(
                    fixture,
                    child,
                    worktree.executionRoot(),
                    isolatedPermission,
                    new PermissionProfileRef(fixture.permission.id(), fixture.permission.version()));
            try (var binding = fixture.platform.bindTool(
                    fixture.request(
                            turn,
                            "file_list",
                            new CodingContracts.FileList(".", Optional.empty(), 100),
                            "isolated-list"),
                    isolatedPermission,
                    new CancellationSource())) {
                var returned = binding.result(binding.invoke());
                assertTrue(returned.success(), returned.response().payload().json());
                var files = fixture.json.decode(returned.response().payload(), CodingResults.FileListResult.class);
                assertEquals(
                        List.of("tracked.txt"),
                        files.entries().stream()
                                .map(CodingResults.FileEntry::path)
                                .toList());
            }
            rejectOrdinaryWorkspace(fixture, worktree.executionRoot());
            assertEquals("tracked contents", Files.readString(fixture.root.resolve("tracked.txt")));
        }
    }

    private static void rejectOrdinaryWorkspace(CodingTestFixture fixture, Path root) throws Exception {
        Workspace workspace = fixture.core.createWorkspace(
                fixture.identity("workspace/create", "pretend", Map.of()), "Pretend project", root);
        var thread = fixture.core.createThread(
                fixture.identity("thread/create", "pretend-thread", Map.of()),
                workspace.id(),
                Optional.empty(),
                ThreadExecutionIntent.WORKSPACE,
                "Pretend ordinary workspace");
        var permission = grant(fixture, root);
        var turn = start(
                fixture, thread, root, permission, new PermissionProfileRef(permission.id(), permission.version()));
        try (var binding = fixture.platform.bindTool(
                fixture.request(
                        turn, "file_list", new CodingContracts.FileList(".", Optional.empty(), 100), "pretend-list"),
                permission,
                new CancellationSource())) {
            var returned = binding.result(binding.invoke());
            assertFalse(returned.success());
            assertEquals(
                    "CODING_PERMISSION_DENIED",
                    fixture.json
                            .decode(returned.response().payload(), CodingResults.Failure.class)
                            .errorCode());
        }
    }

    private static PermissionProfile grant(CodingTestFixture fixture, Path root) {
        var clone = fixture.profiles.cloneProfile(
                fixture.identity("permissionProfile/clone", "pretend-clone", Map.of()),
                new PermissionProfileRef(fixture.permission.id(), fixture.permission.version()),
                "pretend-root");
        var permission = new PermissionProfile(
                clone.id(),
                2,
                new FilePermission(List.of(root), List.of(root), true, false),
                clone.network(),
                clone.processes(),
                clone.tools(),
                clone.resources());
        return fixture.profiles.update(
                new CommandIdentity(
                        "permissionProfile/update",
                        "pretend-grant",
                        1,
                        fixture.json.encode(permission).sha256()),
                permission);
    }

    private static AgentTurn start(
            CodingTestFixture fixture,
            ConversationThread thread,
            Path root,
            PermissionProfile permission,
            PermissionProfileRef reference) {
        var descriptor = new ToolDescriptor(
                new ToolIdentity(CodingContracts.EXTENSION_ID, "file_list", 1),
                "Read bounded directory",
                fixture.json.parse("{\"type\":\"object\"}"),
                fixture.json.parse("{\"type\":\"object\"}"),
                ToolRisk.READ_ONLY,
                Set.of("coding"));
        var catalog =
                new ToolCatalogSnapshot(TurnId.random(), 1, List.of(descriptor), permission, fixture.clock.instant());
        var selection = new TurnContractFixtures.Selection(
                fixture.turn.budget(), fixture.turn.role(), fixture.turn.provider(), reference);
        var request = TurnContractFixtures.request(
                        thread.id(),
                        selection,
                        root,
                        TurnContractFixtures.PROMPT_SNAPSHOT,
                        catalog,
                        new CorePayloads.Message(MessageRole.USER, "list", List.of(), Optional.empty()),
                        Optional.empty())
                .withCodingEnvironment(fixture.core
                        .codingEnvironments()
                        .frozen(fixture.turn.id())
                        .inherited());
        var turn = fixture.core.startTurn(fixture.identity("turn/start", "start-" + thread.id(), request), request);
        fixture.journal.transition(turn.id(), TurnStatus.QUEUED, TurnStatus.RUNNING, Optional.empty());
        return fixture.core.findTurn(turn.id()).orElseThrow();
    }

    private static void seedRepository(Path root) throws Exception {
        git(root, "init");
        git(root, "config", "user.name", "JavaClaw Tests");
        git(root, "config", "user.email", "tests@javaclaw.invalid");
        Files.writeString(root.resolve("tracked.txt"), "tracked contents");
        git(root, "add", "tracked.txt");
        git(root, "commit", "-m", "fixture");
    }

    private static void git(Path root, String... arguments) throws Exception {
        var command = new ArrayList<>(List.of("/usr/bin/git"));
        command.addAll(List.of(arguments));
        Path log = Files.createTempFile("javaclaw-git-fixture", ".log");
        Process process = new ProcessBuilder(command)
                .directory(root.toFile())
                .redirectErrorStream(true)
                .redirectOutput(log.toFile())
                .start();
        try {
            assertTrue(process.waitFor(30, TimeUnit.SECONDS), "fixture git timed out");
            assertEquals(0, process.exitValue(), Files.readString(log, StandardCharsets.UTF_8));
        } finally {
            process.destroyForcibly();
            Files.deleteIfExists(log);
        }
    }
}
