package com.javaclaw.server.turn;

import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.javaclaw.api.AgentProfile;
import com.javaclaw.api.AgentProfileRef;
import com.javaclaw.api.AgentProfileSpec;
import com.javaclaw.api.AgentTurn;
import com.javaclaw.api.AutomationExecutionSnapshot;
import com.javaclaw.api.CancellationSource;
import com.javaclaw.api.CancellationToken;
import com.javaclaw.api.ConversationThread;
import com.javaclaw.api.CorePayloads;
import com.javaclaw.api.MessageRole;
import com.javaclaw.api.PermissionProfile;
import com.javaclaw.api.PermissionProfileRef;
import com.javaclaw.api.ProfileLifecycle;
import com.javaclaw.api.ProviderAdapter;
import com.javaclaw.api.ProviderEndpointSpec;
import com.javaclaw.api.ProviderRef;
import com.javaclaw.api.ProviderRole;
import com.javaclaw.api.SandboxExecutor;
import com.javaclaw.api.ThreadExecutionIntent;
import com.javaclaw.api.ToolCatalogSnapshot;
import com.javaclaw.api.TurnBudget;
import com.javaclaw.api.TurnId;
import com.javaclaw.api.Workspace;
import com.javaclaw.protocol.CanonicalJson;
import com.javaclaw.protocol.CoreRpcContracts;
import com.javaclaw.protocol.WriteCommand;
import com.javaclaw.runtime.ModelCapabilities;
import com.javaclaw.runtime.ModelEventSink;
import com.javaclaw.runtime.ModelGateway;
import com.javaclaw.runtime.ModelInvocation;
import com.javaclaw.runtime.ModelInvocationResult;
import com.javaclaw.runtime.ToolCatalogPort;
import com.javaclaw.runtime.TurnExecutionCommand;
import com.javaclaw.server.instructions.ProjectInstructionResolver;
import com.javaclaw.server.persistence.AgentProfileService;
import com.javaclaw.server.persistence.AttachmentService;
import com.javaclaw.server.persistence.CommandIdentity;
import com.javaclaw.server.persistence.CoreCommandService;
import com.javaclaw.server.persistence.H2Database;
import com.javaclaw.server.persistence.ManagedWorktreeService;
import com.javaclaw.server.persistence.PermissionProfileService;
import com.javaclaw.server.persistence.ProfileBindingService;
import com.javaclaw.server.persistence.ProviderService;
import com.javaclaw.server.persistence.TurnStartRequest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TurnCommandFactoryOrchestratedTest {
    private static final Instant NOW = Instant.parse("2026-09-02T03:00:00Z");

    @TempDir
    Path temporaryDirectory;

    private CanonicalJson json;
    private CoreCommandService core;
    private AgentProfileService profiles;
    private RecordingCatalog catalogs;
    private RecordingModel models;
    private TurnCommandFactory factory;
    private Workspace workspace;
    private ConversationThread thread;
    private AgentProfile profile;

    @BeforeEach
    void initializeDataV5() throws Exception {
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        json = new CanonicalJson();
        H2Database database = new H2Database(temporaryDirectory.resolve("data-v5"));
        database.initialize();
        core = new CoreCommandService(database, json, clock);
        PermissionProfileService permissions = new PermissionProfileService(database, json, clock);
        permissions.installStandardProfile();
        PermissionProfile standard = permissions.require(PermissionProfileService.STANDARD_PROFILE_ID, 1);
        ProviderService providers = new ProviderService(database, json, clock);
        providers.create(identity("provider/create", "provider", Map.of()), "provider", providerSpec());
        profiles = new AgentProfileService(database, providers, permissions, json, clock);
        profile = profiles.create(identity("profile/create", "profile", Map.of()), "profile", profileSpec(standard));
        ProfileBindingService bindings = new ProfileBindingService(database, core, profiles, json, clock);
        ManagedWorktreeService worktrees = new ManagedWorktreeService(
                database, new AttachmentService(database, json, clock), json, clock, unavailableSandbox());
        catalogs = new RecordingCatalog();
        models = new RecordingModel();
        factory = new TurnCommandFactory(
                new TurnPlatformServices(
                        core,
                        profiles,
                        bindings,
                        permissions,
                        new ProjectInstructionResolver(temporaryDirectory.resolve("state"), clock),
                        worktrees),
                models,
                catalogs,
                "system",
                json);
        createWorkspaceAndThread();
    }

    @Test
    void automation快照解析和执行命令始终复用冻结Provider权限预算与目录() {
        AgentProfileRef profileRef = new AgentProfileRef(profile.id(), profile.revision());
        AutomationExecutionSnapshot snapshot =
                factory.freezeAutomation(workspace.id(), profileRef, new CancellationSource());
        CoreRpcContracts.TurnStartPayload payload =
                new CoreRpcContracts.TurnStartPayload(thread.id(), Optional.of(profileRef), "执行自动化步骤");
        CorePayloads.Message message =
                new CorePayloads.Message(MessageRole.USER, payload.message(), List.of(), Optional.empty());
        TurnStartRequest resolved = factory.resolveOrchestrated(payload, message, snapshot);
        AgentTurn turn = core.startTurn(identity("turn/start", "automation-turn", payload), resolved);

        TurnExecutionCommand command = factory.createOrchestrated(turn, payload, snapshot);

        assertEquals(snapshot.provider(), command.provider());
        assertEquals(snapshot.turnBudget(), command.turn().budget());
        assertEquals(snapshot.permissionProfile(), command.turn().permissionProfile());
        assertEquals(turn.id(), command.toolCatalog().turnId());
        assertEquals(
                snapshot.toolCatalog().catalogRevision(), command.toolCatalog().catalogRevision());
        assertEquals(1, catalogs.freezes.get());
        assertEquals(2, catalogs.bindings.get());
        assertEquals(
                List.of(
                        snapshot.provider().routeKey(),
                        snapshot.provider().routeKey(),
                        snapshot.provider().routeKey()),
                models.lookups);
    }

    @Test
    void automation解析拒绝Profile不一致和权威配置漂移() {
        AgentProfileRef profileRef = new AgentProfileRef(profile.id(), profile.revision());
        AutomationExecutionSnapshot snapshot =
                factory.freezeAutomation(workspace.id(), profileRef, new CancellationSource());
        CoreRpcContracts.TurnStartPayload missingProfile =
                new CoreRpcContracts.TurnStartPayload(thread.id(), Optional.empty(), "missing");
        CorePayloads.Message message =
                new CorePayloads.Message(MessageRole.USER, "missing", List.of(), Optional.empty());
        AutomationExecutionSnapshot wrongProvider = new AutomationExecutionSnapshot(
                snapshot.profile(),
                new ProviderRef("other", 1, "model"),
                snapshot.permissionProfile(),
                snapshot.turnBudget(),
                snapshot.toolCatalog(),
                snapshot.unattendedExecutionScope());

        assertThrows(
                IllegalArgumentException.class, () -> factory.resolveOrchestrated(missingProfile, message, snapshot));
        CoreRpcContracts.TurnStartPayload explicit =
                new CoreRpcContracts.TurnStartPayload(thread.id(), Optional.of(profileRef), "missing");
        assertThrows(
                IllegalArgumentException.class, () -> factory.resolveOrchestrated(explicit, message, wrongProvider));
    }

    @Test
    void freeze拒绝缺失Workspace和取消信号且Profile停用立即生效() {
        AgentProfileRef profileRef = new AgentProfileRef(profile.id(), profile.revision());
        CancellationSource cancelled = new CancellationSource();
        cancelled.cancel("test cancelled");

        assertThrows(
                IllegalArgumentException.class,
                () -> factory.freezeAutomation(
                        com.javaclaw.api.WorkspaceId.random(), profileRef, new CancellationSource()));
        assertThrows(
                com.javaclaw.api.TurnCancelledException.class,
                () -> factory.freezeAutomation(workspace.id(), profileRef, cancelled));

        AgentProfile disabled = profiles.update(
                identity("profile/update", "disable-profile", profile.revision(), Map.of()),
                profile.id(),
                profile.spec(),
                ProfileLifecycle.DISABLED);
        assertEquals(ProfileLifecycle.DISABLED, disabled.lifecycle());
        assertThrows(
                IllegalArgumentException.class,
                () -> factory.freezeAutomation(
                        workspace.id(),
                        new AgentProfileRef(disabled.id(), disabled.revision()),
                        new CancellationSource()));
    }

    @Test
    void createOrchestrated拒绝请求Prompt执行根与工具目录身份漂移() {
        ExecutionFixture execution = execution("identity-drift");
        CoreRpcContracts.TurnStartPayload wrongThread = new CoreRpcContracts.TurnStartPayload(
                com.javaclaw.api.ThreadId.random(),
                execution.payload().profile(),
                execution.payload().message());
        CoreRpcContracts.TurnStartPayload wrongProfile = new CoreRpcContracts.TurnStartPayload(
                thread.id(),
                Optional.of(new AgentProfileRef("other-profile", 1)),
                execution.payload().message());

        assertThrows(
                IllegalArgumentException.class,
                () -> factory.createOrchestrated(execution.turn(), wrongThread, execution.snapshot()));
        assertThrows(
                IllegalArgumentException.class,
                () -> factory.createOrchestrated(execution.turn(), wrongProfile, execution.snapshot()));
        assertThrows(
                IllegalStateException.class,
                () -> factory.createOrchestrated(
                        altered(execution.turn(), TurnMutation.PROMPT), execution.payload(), execution.snapshot()));
        assertThrows(
                IllegalStateException.class,
                () -> factory.createOrchestrated(
                        altered(execution.turn(), TurnMutation.ROOT), execution.payload(), execution.snapshot()));
        assertThrows(
                IllegalStateException.class,
                () -> factory.createOrchestrated(
                        altered(execution.turn(), TurnMutation.CATALOG), execution.payload(), execution.snapshot()));
    }

    @Test
    void createOrchestrated逐字段拒绝Provider权限与预算漂移() {
        ExecutionFixture execution = execution("configuration-drift");

        assertConfigurationRejected(execution, TurnMutation.PROVIDER);
        assertConfigurationRejected(execution, TurnMutation.PERMISSION);
        assertConfigurationRejected(execution, TurnMutation.BUDGET);
    }

    private ExecutionFixture execution(String key) {
        AgentProfileRef profileRef = new AgentProfileRef(profile.id(), profile.revision());
        AutomationExecutionSnapshot snapshot =
                factory.freezeAutomation(workspace.id(), profileRef, new CancellationSource());
        CoreRpcContracts.TurnStartPayload payload =
                new CoreRpcContracts.TurnStartPayload(thread.id(), Optional.of(profileRef), key);
        CorePayloads.Message message = new CorePayloads.Message(MessageRole.USER, key, List.of(), Optional.empty());
        TurnStartRequest resolved = factory.resolveOrchestrated(payload, message, snapshot);
        AgentTurn turn = core.startTurn(identity("turn/start", key, payload), resolved);
        return new ExecutionFixture(turn, payload, snapshot);
    }

    private void assertConfigurationRejected(ExecutionFixture execution, TurnMutation mutation) {
        assertThrows(
                IllegalArgumentException.class,
                () -> factory.createOrchestrated(
                        altered(execution.turn(), mutation), execution.payload(), execution.snapshot()));
    }

    private AgentTurn altered(AgentTurn source, TurnMutation mutation) {
        return new AgentTurn(
                source.id(),
                source.threadId(),
                source.status(),
                source.revision(),
                mutation == TurnMutation.BUDGET
                        ? new TurnBudget(4_001, 1_000, 2, 0, Duration.ofMinutes(1))
                        : source.budget(),
                source.profile(),
                mutation == TurnMutation.PROVIDER ? new ProviderRef("other-provider", 1, "model") : source.provider(),
                mutation == TurnMutation.PERMISSION
                        ? new PermissionProfileRef("other-permission", 1)
                        : source.permissionProfile(),
                mutation == TurnMutation.ROOT ? temporaryDirectory.resolve("other-root") : source.executionRoot(),
                mutation == TurnMutation.PROMPT ? "f".repeat(64) : source.promptManifestDigest(),
                mutation == TurnMutation.CATALOG ? "e".repeat(64) : source.toolCatalogDigest(),
                source.errorCode(),
                source.createdAt(),
                source.updatedAt());
    }

    private void createWorkspaceAndThread() throws Exception {
        Path root = Files.createDirectories(temporaryDirectory.resolve("workspace"));
        CoreRpcContracts.WorkspaceCreatePayload workspacePayload =
                new CoreRpcContracts.WorkspaceCreatePayload("Automation", root);
        workspace = core.createWorkspace(
                identity("workspace/create", "workspace", workspacePayload), workspacePayload.name(), root);
        CoreRpcContracts.ThreadCreatePayload threadPayload = new CoreRpcContracts.ThreadCreatePayload(
                workspace.id(), Optional.empty(), ThreadExecutionIntent.WORKSPACE, "Automation");
        thread = core.createThread(
                identity("thread/create", "thread", threadPayload),
                workspace.id(),
                Optional.empty(),
                ThreadExecutionIntent.WORKSPACE,
                "Automation");
    }

    private AgentProfileSpec profileSpec(PermissionProfile permission) {
        return new AgentProfileSpec(
                "Automation",
                "system profile",
                new ProviderRef("provider", 1, "model"),
                new PermissionProfileRef(permission.id(), permission.version()),
                Set.of(),
                new TurnBudget(4_000, 1_000, 2, 0, Duration.ofMinutes(1)));
    }

    private static ProviderEndpointSpec providerSpec() {
        return new ProviderEndpointSpec(
                "Provider",
                ProviderAdapter.OPENAI_COMPATIBLE,
                Optional.empty(),
                Set.of(ProviderRole.CHAT),
                List.of("model"),
                Optional.empty(),
                Duration.ofSeconds(30),
                0,
                Map.of());
    }

    private CommandIdentity identity(String method, String key, Object payload) {
        return identity(method, key, 0, payload);
    }

    private CommandIdentity identity(String method, String key, long revision, Object payload) {
        return CommandIdentity.from(method, new WriteCommand(key, revision, json.encode(payload)), json);
    }

    private static SandboxExecutor unavailableSandbox() {
        return (SandboxExecutor) Proxy.newProxyInstance(
                TurnCommandFactoryOrchestratedTest.class.getClassLoader(),
                new Class<?>[] {SandboxExecutor.class},
                (proxy, method, arguments) -> {
                    throw new AssertionError("sandbox should not be called: " + method.getName());
                });
    }

    private final class RecordingCatalog implements ToolCatalogPort {
        private final AtomicInteger freezes = new AtomicInteger();
        private final AtomicInteger bindings = new AtomicInteger();

        @Override
        public ToolCatalogSnapshot freeze(
                TurnId turnId,
                com.javaclaw.api.WorkspaceId workspaceId,
                PermissionProfile permissions,
                CancellationToken cancellation) {
            cancellation.throwIfCancelled();
            freezes.incrementAndGet();
            return new ToolCatalogSnapshot(turnId, 17, List.of(), permissions, NOW);
        }

        @Override
        public ToolCatalogSnapshot freeze(
                TurnId turnId, PermissionProfile permissions, CancellationToken cancellation) {
            throw new AssertionError("workspace-free freeze must be unavailable");
        }

        @Override
        public ToolCatalogSnapshot bindFrozen(
                TurnId turnId,
                com.javaclaw.api.WorkspaceId workspaceId,
                ToolCatalogSnapshot frozen,
                PermissionProfile currentPermissions,
                CancellationToken cancellation) {
            cancellation.throwIfCancelled();
            bindings.incrementAndGet();
            return new ToolCatalogSnapshot(
                    turnId, frozen.catalogRevision(), frozen.tools(), currentPermissions, frozen.capturedAt());
        }

        @Override
        public List<com.javaclaw.api.ToolDescriptor> initialTools(ToolCatalogSnapshot snapshot) {
            return List.of();
        }
    }

    private final class RecordingModel implements ModelGateway {
        private final List<String> lookups = new java.util.ArrayList<>();

        @Override
        public ModelCapabilities capabilities(String modelId) {
            lookups.add(modelId);
            return new ModelCapabilities(false, false, false, false, false, false, false);
        }

        @Override
        public ModelInvocationResult invoke(
                TurnId turnId, ModelInvocation invocation, ModelEventSink events, CancellationToken cancellation) {
            throw new AssertionError("model should not be invoked while building a command");
        }
    }

    private enum TurnMutation {
        PROVIDER,
        PERMISSION,
        BUDGET,
        ROOT,
        PROMPT,
        CATALOG
    }

    private record ExecutionFixture(
            AgentTurn turn, CoreRpcContracts.TurnStartPayload payload, AutomationExecutionSnapshot snapshot) {}
}
