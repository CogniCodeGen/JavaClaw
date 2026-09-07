package com.javaclaw.server.turn;

import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.javaclaw.api.AgentRole;
import com.javaclaw.api.AgentRoleRef;
import com.javaclaw.api.AgentRoleSpec;
import com.javaclaw.api.AutomationExecutionSnapshot;
import com.javaclaw.api.CancellationToken;
import com.javaclaw.api.PermissionProfile;
import com.javaclaw.api.ProviderAdapter;
import com.javaclaw.api.ProviderEndpointSpec;
import com.javaclaw.api.ProviderLifecycle;
import com.javaclaw.api.RoleLifecycle;
import com.javaclaw.api.SandboxCommand;
import com.javaclaw.api.SandboxExecutor;
import com.javaclaw.api.SandboxResult;
import com.javaclaw.api.SandboxSession;
import com.javaclaw.api.Workspace;
import com.javaclaw.api.WorkspaceId;
import com.javaclaw.extension.spi.AutomationExecutionPolicyPort;
import com.javaclaw.extension.spi.AutomationRoleOption;
import com.javaclaw.protocol.CanonicalJson;
import com.javaclaw.server.persistence.AgentRoleService;
import com.javaclaw.server.persistence.AttachmentService;
import com.javaclaw.server.persistence.CommandIdentity;
import com.javaclaw.server.persistence.CoreCommandService;
import com.javaclaw.server.persistence.H2Database;
import com.javaclaw.server.persistence.ManagedWorktreeService;
import com.javaclaw.server.persistence.PermissionProfileService;
import com.javaclaw.server.persistence.ProviderService;

import static com.javaclaw.server.ProviderEndpointTestFixtures.chat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AutomationRoleCatalogTest {
    private static final Instant NOW = Instant.parse("2026-09-01T00:00:00Z");

    @TempDir
    Path temporaryDirectory;

    private CanonicalJson json;
    private CoreCommandService core;
    private ProviderService providers;
    private AgentRoleService roles;
    private Workspace workspace;
    private ServerTurnOrchestrationPort port;

    @BeforeEach
    void initializeDataV6() throws Exception {
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        json = new CanonicalJson();
        H2Database database = new H2Database(temporaryDirectory.resolve("data-v6"));
        database.initialize();
        core = new CoreCommandService(database, json, clock);
        providers = new ProviderService(database, reference -> true, json, clock);
        PermissionProfileService permissions = new PermissionProfileService(database, json, clock);
        permissions.installStandardProfile();
        roles = new AgentRoleService(database, providers, json, clock);
        Files.createDirectories(temporaryDirectory.resolve("workspace"));
        workspace = core.createWorkspace(
                identity("workspace/create", "workspace", 0, Map.of("name", "Workspace")),
                "Workspace",
                temporaryDirectory.resolve("workspace"));
        ManagedWorktreeService worktrees = new ManagedWorktreeService(
                database, new AttachmentService(database, json, clock), json, clock, new UnavailableSandbox());
        port = new ServerTurnOrchestrationPort(
                new TurnPlatformServices(
                        core,
                        roles,
                        new com.javaclaw.server.persistence.ExecutionConfigurationService(
                                database, core, roles, json, clock),
                        permissions,
                        new com.javaclaw.server.instructions.ProjectInstructionResolver(temporaryDirectory, clock),
                        worktrees),
                providers,
                unavailableDispatcher(),
                json);
        providers.create(
                identity("provider/create", "provider", 0, Map.of("id", "provider")),
                "provider",
                providerSpec(),
                ProviderLifecycle.ACTIVE);
    }

    @Test
    void catalogOnlyReturnsLatestActiveRolesWithExactNameAndRevision() {
        AgentRole active = roles.create(
                identity("agent/role/create", "active-create", 0, Map.of("id", "active")), "active", roleSpec("旧名称"));
        AgentRole latest = roles.update(
                identity("agent/role/update", "active-update", active.revision(), Map.of("id", "active")),
                active.id(),
                roleSpec("当前名称"),
                RoleLifecycle.ACTIVE);
        AgentRole archived = roles.create(
                identity("agent/role/create", "archived-create", 0, Map.of("id", "archived")),
                "archived",
                roleSpec("已归档"));
        roles.archive(
                identity("agent/role/archive", "archived-archive", archived.revision(), Map.of("id", "archived")),
                archived.id());
        AgentRole disabled = roles.create(
                identity("agent/role/create", "disabled-create", 0, Map.of("id", "disabled")),
                "disabled",
                roleSpec("已停用"));
        roles.update(
                identity("agent/role/update", "disabled-update", disabled.revision(), Map.of("id", "disabled")),
                disabled.id(),
                disabled.spec(),
                RoleLifecycle.DISABLED);

        assertEquals(
                List.of(new AutomationRoleOption(
                        new AgentRoleRef(latest.id(), latest.revision()),
                        latest.spec().name())),
                port.roles(workspace.id()).stream()
                        .filter(option -> option.role().id().equals("active"))
                        .toList());
    }

    @Test
    void missingOrArchivedWorkspaceCannotReadNewExecutionCatalog() {
        assertThrows(IllegalArgumentException.class, () -> port.roles(WorkspaceId.random()));
        Workspace archived = core.archiveWorkspace(
                identity("workspace/archive", "workspace-archive", workspace.revision(), Map.of()), workspace.id());

        assertEquals(com.javaclaw.api.WorkspaceLifecycle.ARCHIVED, archived.lifecycle());
        assertThrows(IllegalArgumentException.class, () -> port.roles(workspace.id()));
    }

    @Test
    void 独立模型权限目录与延迟端口保持工作区边界() {
        assertEquals(
                List.of("provider"),
                port.providers(workspace.id()).stream().map(value -> value.id()).toList());
        assertEquals(
                List.of("standard"),
                port.permissions(workspace.id()).stream()
                        .map(value -> value.id())
                        .toList());
        assertThrows(IllegalArgumentException.class, () -> port.providers(WorkspaceId.random()));
        assertThrows(IllegalArgumentException.class, () -> port.permissions(WorkspaceId.random()));
        DeferredAutomationExecutionPolicyPort deferred = new DeferredAutomationExecutionPolicyPort();
        assertThrows(IllegalStateException.class, () -> deferred.providers(workspace.id()));
        assertThrows(IllegalStateException.class, () -> deferred.permissions(workspace.id()));
        deferred.bind(port);
        assertEquals(port.providers(workspace.id()), deferred.providers(workspace.id()));
        assertEquals(port.permissions(workspace.id()), deferred.permissions(workspace.id()));
        core.archiveWorkspace(
                identity("workspace/archive", "archive-catalog", workspace.revision(), Map.of()), workspace.id());
        assertThrows(IllegalArgumentException.class, () -> deferred.providers(workspace.id()));
        assertThrows(IllegalArgumentException.class, () -> deferred.permissions(workspace.id()));
    }

    @Test
    void deferredPortFailsClosedUntilBoundAndThenForwardsCatalog() {
        DeferredAutomationExecutionPolicyPort deferred = new DeferredAutomationExecutionPolicyPort();
        AutomationRoleOption expected = new AutomationRoleOption(new AgentRoleRef("profile", 3), "自动化 Profile");

        assertThrows(IllegalStateException.class, () -> deferred.roles(workspace.id()));
        deferred.bind(new CatalogPolicy(List.of(expected)));

        assertEquals(List.of(expected), deferred.roles(workspace.id()));
        assertThrows(IllegalStateException.class, () -> deferred.bind(new CatalogPolicy(List.of())));
    }

    @Test
    void policyWithoutCatalogOverrideFailsClosed() {
        AutomationExecutionPolicyPort freezeOnly = (workspaceId, profile, cancellation) -> {
            throw new AssertionError("freeze should not be called");
        };

        assertThrows(IllegalStateException.class, () -> freezeOnly.roles(workspace.id()));
    }

    private AgentRoleSpec roleSpec(String displayName) {
        return new AgentRoleSpec(
                displayName,
                "",
                "",
                Optional.empty(),
                Optional.empty(),
                new com.javaclaw.api.CapabilityNarrowing(Optional.of(Set.of()), Optional.empty()),
                com.javaclaw.api.PermissionConstraint.INHERIT,
                java.util.Map.of());
    }

    private static ProviderEndpointSpec providerSpec() {
        return chat("Provider", ProviderAdapter.OPENAI_COMPATIBLE, "test-model");
    }

    private CommandIdentity identity(String method, String key, long expectedRevision, Object payload) {
        return new CommandIdentity(
                method, key, expectedRevision, json.encode(payload).sha256());
    }

    private static AwaitableTurnDispatcher unavailableDispatcher() {
        return (AwaitableTurnDispatcher) Proxy.newProxyInstance(
                AutomationRoleCatalogTest.class.getClassLoader(),
                new Class<?>[] {AwaitableTurnDispatcher.class},
                (proxy, method, arguments) -> {
                    throw new AssertionError("dispatcher should not be called: " + method.getName());
                });
    }

    private record CatalogPolicy(List<AutomationRoleOption> options) implements AutomationExecutionPolicyPort {
        private CatalogPolicy {
            options = List.copyOf(options);
        }

        @Override
        public List<AutomationRoleOption> roles(WorkspaceId workspaceId) {
            return options;
        }

        @Override
        public AutomationExecutionSnapshot freeze(
                WorkspaceId workspaceId,
                com.javaclaw.api.ExecutionOverrides execution,
                CancellationToken cancellation) {
            throw new AssertionError("freeze should not be called");
        }
    }

    private static final class UnavailableSandbox implements SandboxExecutor {
        @Override
        public SandboxResult execute(
                SandboxCommand command, PermissionProfile permission, CancellationToken cancellation) {
            throw new AssertionError("sandbox should not be called");
        }

        @Override
        public SandboxSession open(
                SandboxCommand command, PermissionProfile permission, CancellationToken cancellation) {
            throw new AssertionError("sandbox should not be called");
        }
    }
}
