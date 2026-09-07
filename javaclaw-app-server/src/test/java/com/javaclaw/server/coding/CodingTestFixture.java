package com.javaclaw.server.coding;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.javaclaw.api.AgentTurn;
import com.javaclaw.api.ApprovalRequirement;
import com.javaclaw.api.CancellationSource;
import com.javaclaw.api.CorePayloads;
import com.javaclaw.api.FilePermission;
import com.javaclaw.api.ItemSchemaRegistry;
import com.javaclaw.api.MessageRole;
import com.javaclaw.api.PermissionProfile;
import com.javaclaw.api.PermissionProfileRef;
import com.javaclaw.api.ProcessPermission;
import com.javaclaw.api.ResourceLimits;
import com.javaclaw.api.ThreadExecutionIntent;
import com.javaclaw.api.ToolCallRequest;
import com.javaclaw.api.ToolCatalogSnapshot;
import com.javaclaw.api.ToolIdentity;
import com.javaclaw.api.ToolPermission;
import com.javaclaw.api.ToolRisk;
import com.javaclaw.api.TurnBudget;
import com.javaclaw.api.TurnId;
import com.javaclaw.api.TurnStatus;
import com.javaclaw.api.Workspace;
import com.javaclaw.builtin.contracts.CodingContracts;
import com.javaclaw.builtin.extensions.BuiltinExtensions;
import com.javaclaw.nativehost.sandbox.PlatformSandboxExecutor;
import com.javaclaw.protocol.CanonicalJson;
import com.javaclaw.server.TurnContractFixtures;
import com.javaclaw.server.extension.contract.GovernedExtensionResponse;
import com.javaclaw.server.persistence.AttachmentService;
import com.javaclaw.server.persistence.CommandIdentity;
import com.javaclaw.server.persistence.CoreCommandService;
import com.javaclaw.server.persistence.ExtensionCatalogRepository;
import com.javaclaw.server.persistence.H2Database;
import com.javaclaw.server.persistence.H2TurnJournal;
import com.javaclaw.server.persistence.ManagedWorktreeService;
import com.javaclaw.server.persistence.PermissionProfileService;
import com.javaclaw.server.toolchain.CodingToolchainCatalog;
import com.javaclaw.server.turn.CodingExecutionAuthority;

/** 使用真实 H2、权限和原生文件 Worker 的夹具；不调用模型或下载发行制品。 */
final class CodingTestFixture implements AutoCloseable {
    final CanonicalJson json = new CanonicalJson();
    final Clock clock = Clock.systemUTC();
    final Path root;
    final H2Database database;
    final CoreCommandService core;
    final PermissionProfileService profiles;
    final PermissionProfile permission;
    final Workspace workspace;
    final AgentTurn turn;
    final CodingPlatform platform;
    final H2TurnJournal journal;

    CodingTestFixture(Path temporaryDirectory) throws Exception {
        this(temporaryDirectory, new FixtureToolchains());
    }

    CodingTestFixture(Path temporaryDirectory, CodingToolchainPort toolchains) throws Exception {
        this(temporaryDirectory, toolchains, Set.of());
    }

    CodingTestFixture(Path temporaryDirectory, CodingToolchainPort toolchains, Set<String> repositories)
            throws Exception {
        root = Files.createDirectories(temporaryDirectory.resolve("workspace")).toRealPath();
        database = new H2Database(temporaryDirectory.resolve("data-v6"));
        database.initialize();
        core = new CoreCommandService(database, json, clock);
        profiles = new PermissionProfileService(database, json, clock);
        profiles.installStandardProfile();
        permission = permission(repositories);
        workspace = core.createWorkspace(identity("workspace/create", "workspace", Map.of()), "Coding", root);
        configureRepositories(repositories);
        journal = new H2TurnJournal(database, new ItemSchemaRegistry(), json, clock);
        turn = createTurn("first");
        var attachments = new AttachmentService(database, json, clock);
        var sandbox = new PlatformSandboxExecutor();
        var worktrees = new ManagedWorktreeService(database, attachments, json, clock, sandbox);
        var catalog = new ExtensionCatalogRepository(database, json, clock);
        BuiltinExtensions.create().stream()
                .filter(bundle -> bundle.descriptor().id().value().equals(CodingContracts.EXTENSION_ID))
                .forEach(bundle -> catalog.installBuiltIn(bundle.descriptor()));
        var authority = new CodingExecutionAuthority(core, profiles, worktrees, catalog, json);
        platform = new CodingPlatform(new CodingPlatform.Dependencies(
                database, core, authority, attachments, toolchains, sandbox, json, clock));
    }

    AgentTurn createTurn(String suffix) {
        var thread = core.createThread(
                identity("thread/create", "thread-" + suffix, Map.of()),
                workspace.id(),
                Optional.empty(),
                ThreadExecutionIntent.WORKSPACE,
                "Coding " + suffix);
        var descriptor = new com.javaclaw.api.ToolDescriptor(
                new ToolIdentity(CodingContracts.EXTENSION_ID, "command_run", 1),
                "测试冻结的 Coding 能力",
                json.parse("{\"type\":\"object\"}"),
                json.parse("{\"type\":\"object\"}"),
                ToolRisk.PROCESS,
                Set.of("coding"));
        var tools = new ToolCatalogSnapshot(TurnId.random(), 1, List.of(descriptor), permission, clock.instant());
        var selection = new TurnContractFixtures.Selection(
                new TurnBudget(
                        100_000,
                        20_000,
                        100,
                        0,
                        Duration.ofMinutes(permission.network().hosts().isEmpty() ? 10 : 30)),
                TurnContractFixtures.ROLE,
                TurnContractFixtures.PROVIDER,
                new PermissionProfileRef(permission.id(), permission.version()));
        var request = TurnContractFixtures.request(
                thread.id(),
                selection,
                root,
                TurnContractFixtures.PROMPT_SNAPSHOT,
                tools,
                new CorePayloads.Message(MessageRole.USER, "查看项目并修复", List.of(), Optional.empty()),
                Optional.empty());
        AgentTurn created = core.startTurn(identity("turn/start", "turn-" + suffix, request), request);
        journal.transition(created.id(), TurnStatus.QUEUED, TurnStatus.RUNNING, Optional.empty());
        return core.findTurn(created.id()).orElseThrow();
    }

    private void configureRepositories(Set<String> repositories) {
        if (repositories.isEmpty()) {
            return;
        }
        var original = CodingToolchainCatalog.bundled().defaultEnvironment();
        var configured = new com.javaclaw.builtin.contracts.CodingEnvironmentContracts.EnvironmentSpec(
                "发行依赖验收", original.toolchains(), repositories, true);
        new com.javaclaw.server.persistence.CodingEnvironmentRepository(database, json, clock)
                .update(workspace.id(), identity("coding/environment", "environment", configured), configured);
    }

    private PermissionProfile permission(Set<String> repositories) {
        PermissionProfile cloned = profiles.cloneProfile(
                identity("permissionProfile/clone", "clone", Map.of()),
                new PermissionProfileRef(PermissionProfileService.STANDARD_PROFILE_ID, 1),
                "coding-test");
        var tools = Set.of(
                "file_list",
                "file_read",
                "file_search",
                "file_apply_patch",
                "command_run",
                "terminal_open",
                "terminal_read",
                "terminal_write",
                "terminal_signal",
                "terminal_resize",
                "terminal_close",
                "dependencies_prepare");
        var updated = new PermissionProfile(
                cloned.id(),
                2,
                new FilePermission(List.of(root), List.of(root), true, false),
                repositories.isEmpty()
                        ? cloned.network()
                        : new com.javaclaw.api.NetworkPermission(repositories, Set.of(443), true),
                new ProcessPermission(
                        Set.of("java", "javac", "python", "node", "mvn", "gradle", "npm", "pnpm", "pip"),
                        true,
                        Duration.ofSeconds(repositories.isEmpty() ? 30 : 600)),
                new ToolPermission(tools, ToolRisk.PROCESS, ApprovalRequirement.NONE),
                new ResourceLimits(1024L * 1024 * 1024, 1024 * 1024, 16, 256));
        return profiles.update(
                new CommandIdentity(
                        "permissionProfile/update",
                        "update",
                        1,
                        json.encode(updated).sha256()),
                updated);
    }

    ToolCallRequest request(AgentTurn owner, String name, Object arguments, String callId) {
        return new ToolCallRequest(
                owner.id(),
                callId,
                new ToolIdentity(CodingContracts.EXTENSION_ID, name, 1),
                json.encode(arguments),
                "effect-" + callId,
                1);
    }

    GovernedExtensionResponse invoke(String name, Object arguments, String callId) throws Exception {
        try (var binding =
                platform.bindTool(request(turn, name, arguments, callId), permission, new CancellationSource())) {
            return binding.result(binding.invoke());
        }
    }

    CommandIdentity identity(String method, String key, Object value) {
        return new CommandIdentity(method, key, 0, json.encode(value).sha256());
    }

    @Override
    public void close() throws Exception {
        platform.close();
    }
}
