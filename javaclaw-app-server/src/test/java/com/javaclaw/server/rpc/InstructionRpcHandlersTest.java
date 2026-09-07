package com.javaclaw.server.rpc;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.javaclaw.api.CancellationToken;
import com.javaclaw.api.InstructionResolution;
import com.javaclaw.api.PermissionProfile;
import com.javaclaw.api.SandboxCommand;
import com.javaclaw.api.SandboxExecutor;
import com.javaclaw.api.SandboxResult;
import com.javaclaw.api.SandboxSession;
import com.javaclaw.api.Workspace;
import com.javaclaw.api.WorkspaceInstructionSettings;
import com.javaclaw.protocol.CanonicalJson;
import com.javaclaw.protocol.CoreRpcContracts;
import com.javaclaw.protocol.InstructionRpcContracts;
import com.javaclaw.protocol.SessionSecretChannel;
import com.javaclaw.protocol.WriteCommand;
import com.javaclaw.server.instructions.ProjectInstructionResolver;
import com.javaclaw.server.persistence.AttachmentService;
import com.javaclaw.server.persistence.CommandIdentity;
import com.javaclaw.server.persistence.CoreCommandService;
import com.javaclaw.server.persistence.H2Database;
import com.javaclaw.server.persistence.ManagedWorktreeService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InstructionRpcHandlersTest {
    private static final Instant NOW = Instant.parse("2026-09-01T00:00:00Z");

    @TempDir
    Path temporaryDirectory;

    private CanonicalJson json;
    private RpcRouter router;
    private Workspace workspace;
    private Path workspaceRoot;

    @BeforeEach
    void 初始化只读项目约定路由() throws Exception {
        workspaceRoot = Files.createDirectories(temporaryDirectory.resolve("workspace"));
        Files.writeString(workspaceRoot.resolve("AGENTS.md"), "仅供 Turn 使用的私密约定", StandardCharsets.UTF_8);
        Path globalRoot = Files.createDirectories(temporaryDirectory.resolve("managed-state"));
        Files.writeString(globalRoot.resolve("AGENTS.md"), "全局私密约定", StandardCharsets.UTF_8);

        json = new CanonicalJson();
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        H2Database database = new H2Database(temporaryDirectory.resolve("data-v6"));
        database.initialize();
        CoreCommandService core = new CoreCommandService(database, json, clock);
        AttachmentService attachments = new AttachmentService(database, json, clock);
        ManagedWorktreeService worktrees =
                new ManagedWorktreeService(database, attachments, json, clock, new UnavailableSandbox());
        ProjectInstructionResolver resolver = new ProjectInstructionResolver(globalRoot, clock);
        router = new InstructionRpcHandlers(core, worktrees, resolver, json)
                .register(RpcRouter.builder())
                .build();

        CoreRpcContracts.WorkspaceCreatePayload payload =
                new CoreRpcContracts.WorkspaceCreatePayload("Instruction RPC", workspaceRoot);
        workspace = core.createWorkspace(
                CommandIdentity.from("workspace/create", new WriteCommand("workspace", 0, json.encode(payload)), json),
                payload.name(),
                payload.root());
    }

    @Test
    void 查询只返回脱敏元数据而不泄露正文或绝对路径() throws Exception {
        try (SessionSecretChannel secrets = SessionSecretChannel.open()) {
            var encoded = router.route(
                    "workspace/instructions/read",
                    json.encode(new InstructionRpcContracts.ReadPayload(workspace.id(), Optional.empty())),
                    secrets);
            InstructionResolution resolution = json.decode(encoded, InstructionResolution.class);

            assertEquals(2, resolution.sources().size());
            assertEquals("AGENTS.md", resolution.sources().get(0).relativePath());
            assertEquals("AGENTS.md", resolution.sources().get(1).relativePath());
            assertTrue(resolution.sources().stream()
                    .allMatch(source -> source.digest().isPresent()));
            assertFalse(encoded.json().contains("私密约定"));
            assertFalse(encoded.json().contains(workspaceRoot.toString()));
            assertFalse(encoded.json().contains(temporaryDirectory.toString()));
        }
    }

    @Test
    void 保存安全fallback后后续解析使用精确设置revision() throws Exception {
        Files.delete(workspaceRoot.resolve("AGENTS.md"));
        Files.writeString(workspaceRoot.resolve("PROJECT.md"), "备用项目约定", StandardCharsets.UTF_8);
        try (SessionSecretChannel secrets = SessionSecretChannel.open()) {
            WorkspaceInstructionSettings initial = json.decode(
                    router.route(
                            "workspace/instructions/settings/read",
                            json.encode(new InstructionRpcContracts.SettingsReadPayload(workspace.id())),
                            secrets),
                    WorkspaceInstructionSettings.class);
            WriteCommand update = new WriteCommand(
                    "instruction-settings",
                    initial.revision(),
                    json.encode(new InstructionRpcContracts.SettingsUpdatePayload(
                            workspace.id(), Optional.of("PROJECT.md"))));

            WorkspaceInstructionSettings saved = json.decode(
                    router.route("workspace/instructions/settings/update", json.encode(update), secrets),
                    WorkspaceInstructionSettings.class);
            InstructionResolution resolution = json.decode(
                    router.route(
                            "workspace/instructions/read",
                            json.encode(new InstructionRpcContracts.ReadPayload(workspace.id(), Optional.empty())),
                            secrets),
                    InstructionResolution.class);

            assertEquals(2, saved.revision());
            assertEquals(Optional.of("PROJECT.md"), saved.fallbackBasename());
            assertEquals("PROJECT.md", resolution.sources().get(1).relativePath());
        }
    }

    private static final class UnavailableSandbox implements SandboxExecutor {
        @Override
        public SandboxResult execute(
                SandboxCommand command, PermissionProfile permission, CancellationToken cancellation) {
            throw new UnsupportedOperationException("项目约定查询不会执行进程");
        }

        @Override
        public SandboxSession open(
                SandboxCommand command, PermissionProfile permission, CancellationToken cancellation) {
            throw new UnsupportedOperationException("项目约定查询不会打开 PTY");
        }
    }
}
