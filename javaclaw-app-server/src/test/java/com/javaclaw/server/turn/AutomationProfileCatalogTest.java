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
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.javaclaw.api.AgentProfile;
import com.javaclaw.api.AgentProfileRef;
import com.javaclaw.api.AgentProfileSpec;
import com.javaclaw.api.AutomationExecutionSnapshot;
import com.javaclaw.api.CancellationToken;
import com.javaclaw.api.PermissionProfile;
import com.javaclaw.api.PermissionProfileRef;
import com.javaclaw.api.ProfileLifecycle;
import com.javaclaw.api.ProviderAdapter;
import com.javaclaw.api.ProviderEndpointSpec;
import com.javaclaw.api.ProviderLifecycle;
import com.javaclaw.api.ProviderRef;
import com.javaclaw.api.SandboxCommand;
import com.javaclaw.api.SandboxExecutor;
import com.javaclaw.api.SandboxResult;
import com.javaclaw.api.SandboxSession;
import com.javaclaw.api.TurnBudget;
import com.javaclaw.api.Workspace;
import com.javaclaw.api.WorkspaceId;
import com.javaclaw.extension.spi.AutomationExecutionPolicyPort;
import com.javaclaw.extension.spi.AutomationProfileOption;
import com.javaclaw.protocol.CanonicalJson;
import com.javaclaw.server.persistence.AgentProfileService;
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

class AutomationProfileCatalogTest {
    private static final Instant NOW = Instant.parse("2026-09-01T00:00:00Z");

    @TempDir
    Path temporaryDirectory;

    private CanonicalJson json;
    private CoreCommandService core;
    private ProviderService providers;
    private AgentProfileService profiles;
    private Workspace workspace;
    private ServerTurnOrchestrationPort port;

    @BeforeEach
    void initializeDataV5() throws Exception {
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        json = new CanonicalJson();
        H2Database database = new H2Database(temporaryDirectory.resolve("data-v5"));
        database.initialize();
        core = new CoreCommandService(database, json, clock);
        providers = new ProviderService(database, reference -> true, json, clock);
        PermissionProfileService permissions = new PermissionProfileService(database, json, clock);
        permissions.installStandardProfile();
        profiles = new AgentProfileService(database, providers, permissions, json, clock);
        Files.createDirectories(temporaryDirectory.resolve("workspace"));
        workspace = core.createWorkspace(
                identity("workspace/create", "workspace", 0, Map.of("name", "Workspace")),
                "Workspace",
                temporaryDirectory.resolve("workspace"));
        ManagedWorktreeService worktrees = new ManagedWorktreeService(
                database, new AttachmentService(database, json, clock), json, clock, new UnavailableSandbox());
        port = new ServerTurnOrchestrationPort(core, profiles, worktrees, unavailableDispatcher(), json);
        providers.create(
                identity("provider/create", "provider", 0, Map.of("id", "provider")),
                "provider",
                providerSpec(),
                ProviderLifecycle.ACTIVE);
    }

    @Test
    void catalogOnlyReturnsLatestActiveProfilesWithExactNameAndRevision() {
        AgentProfile active = profiles.create(
                identity("profile/create", "active-create", 0, Map.of("id", "active")), "active", profileSpec("旧名称"));
        AgentProfile latest = profiles.update(
                identity("profile/update", "active-update", active.revision(), Map.of("id", "active")),
                active.id(),
                profileSpec("当前名称"),
                ProfileLifecycle.ACTIVE);
        AgentProfile archived = profiles.create(
                identity("profile/create", "archived-create", 0, Map.of("id", "archived")),
                "archived",
                profileSpec("已归档"));
        profiles.archive(
                identity("profile/archive", "archived-archive", archived.revision(), Map.of("id", "archived")),
                archived.id());
        AgentProfile disabled = profiles.create(
                identity("profile/create", "disabled-create", 0, Map.of("id", "disabled")),
                "disabled",
                profileSpec("已停用"));
        profiles.update(
                identity("profile/update", "disabled-update", disabled.revision(), Map.of("id", "disabled")),
                disabled.id(),
                disabled.spec(),
                ProfileLifecycle.DISABLED);

        assertEquals(
                List.of(new AutomationProfileOption(
                        new AgentProfileRef(latest.id(), latest.revision()),
                        latest.spec().displayName())),
                port.profiles(workspace.id()));
    }

    @Test
    void missingOrArchivedWorkspaceCannotReadNewExecutionCatalog() {
        assertThrows(IllegalArgumentException.class, () -> port.profiles(WorkspaceId.random()));
        Workspace archived = core.archiveWorkspace(
                identity("workspace/archive", "workspace-archive", workspace.revision(), Map.of()), workspace.id());

        assertEquals(com.javaclaw.api.WorkspaceLifecycle.ARCHIVED, archived.lifecycle());
        assertThrows(IllegalArgumentException.class, () -> port.profiles(workspace.id()));
    }

    @Test
    void deferredPortFailsClosedUntilBoundAndThenForwardsCatalog() {
        DeferredAutomationExecutionPolicyPort deferred = new DeferredAutomationExecutionPolicyPort();
        AutomationProfileOption expected =
                new AutomationProfileOption(new AgentProfileRef("profile", 3), "自动化 Profile");

        assertThrows(IllegalStateException.class, () -> deferred.profiles(workspace.id()));
        deferred.bind(new CatalogPolicy(List.of(expected)));

        assertEquals(List.of(expected), deferred.profiles(workspace.id()));
        assertThrows(IllegalStateException.class, () -> deferred.bind(new CatalogPolicy(List.of())));
    }

    @Test
    void policyWithoutCatalogOverrideFailsClosed() {
        AutomationExecutionPolicyPort freezeOnly = (workspaceId, profile, cancellation) -> {
            throw new AssertionError("freeze should not be called");
        };

        assertThrows(IllegalStateException.class, () -> freezeOnly.profiles(workspace.id()));
    }

    private AgentProfileSpec profileSpec(String displayName) {
        return new AgentProfileSpec(
                displayName,
                "",
                new ProviderRef("provider", 1, "test-model"),
                new PermissionProfileRef(PermissionProfileService.STANDARD_PROFILE_ID, 1),
                Set.of(),
                new TurnBudget(4_000, 1_000, 2, 0, Duration.ofMinutes(1)));
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
                AutomationProfileCatalogTest.class.getClassLoader(),
                new Class<?>[] {AwaitableTurnDispatcher.class},
                (proxy, method, arguments) -> {
                    throw new AssertionError("dispatcher should not be called: " + method.getName());
                });
    }

    private record CatalogPolicy(List<AutomationProfileOption> options) implements AutomationExecutionPolicyPort {
        private CatalogPolicy {
            options = List.copyOf(options);
        }

        @Override
        public List<AutomationProfileOption> profiles(WorkspaceId workspaceId) {
            return options;
        }

        @Override
        public AutomationExecutionSnapshot freeze(
                WorkspaceId workspaceId, AgentProfileRef profile, CancellationToken cancellation) {
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
