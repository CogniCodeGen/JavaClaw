package com.javaclaw.server.turn;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.javaclaw.api.AgentRole;
import com.javaclaw.api.AgentRoleSpec;
import com.javaclaw.api.ApprovalPolicy;
import com.javaclaw.api.CapabilityNarrowing;
import com.javaclaw.api.ConfigurationSource;
import com.javaclaw.api.ExecutionOverrides;
import com.javaclaw.api.ModelPreference;
import com.javaclaw.api.PermissionConstraint;
import com.javaclaw.api.ProviderAdapter;
import com.javaclaw.api.ProviderLifecycle;
import com.javaclaw.api.ProviderRef;
import com.javaclaw.api.ReasoningPreference;
import com.javaclaw.api.RoleLifecycle;
import com.javaclaw.api.ThreadExecutionIntent;
import com.javaclaw.api.TurnBudget;
import com.javaclaw.api.Workspace;
import com.javaclaw.protocol.CanonicalJson;
import com.javaclaw.server.ProviderEndpointTestFixtures;
import com.javaclaw.server.persistence.AgentRoleService;
import com.javaclaw.server.persistence.CommandIdentity;
import com.javaclaw.server.persistence.CoreCommandService;
import com.javaclaw.server.persistence.ExecutionConfigurationService;
import com.javaclaw.server.persistence.H2Database;
import com.javaclaw.server.persistence.PermissionProfileService;
import com.javaclaw.server.persistence.PersistenceException;
import com.javaclaw.server.persistence.ProviderService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentConfigurationResolverTest {
    @TempDir
    Path directory;

    private final CanonicalJson json = new CanonicalJson();
    private final Clock clock = Clock.fixed(Instant.parse("2026-09-01T00:00:00Z"), ZoneOffset.UTC);
    private AgentRoleService roles;
    private CoreCommandService core;
    private ExecutionConfigurationService defaults;
    private AgentConfigurationResolver resolver;
    private Workspace workspace;
    private ThreadExecutionScope scope;

    @BeforeEach
    void initialize() {
        H2Database database = new H2Database(directory.resolve("data-v6"));
        database.initialize();
        ProviderService providers = new ProviderService(database, ignored -> true, json, clock);
        providers.create(
                identity("provider", 0),
                "models",
                ProviderEndpointTestFixtures.chat(
                        "Models", ProviderAdapter.OPENAI_COMPATIBLE, "parent", "child", "spawn", "locked"),
                ProviderLifecycle.ACTIVE);
        roles = new AgentRoleService(database, providers, json, clock);
        core = new CoreCommandService(database, json, clock);
        defaults = new ExecutionConfigurationService(database, core, roles, json, clock);
        PermissionProfileService permissions = new PermissionProfileService(database, json, clock);
        permissions.installStandardProfile();
        resolver = new AgentConfigurationResolver(roles, defaults, permissions, core);
        workspace = core.createWorkspace(identity("workspace", 0), "Workspace", directory.resolve("project"));
        scope = ThreadExecutionScope.workspace(workspace);
        defaults.ensureInstallationDefaults(model("parent"));
    }

    @Test
    void explicitThenThreadThenWorkspaceThenInstallationChooseIndependentModel() {
        var thread = core.createThread(
                identity("thread", 0), workspace.id(), Optional.empty(), ThreadExecutionIntent.WORKSPACE, "Thread");
        assertEquals(
                provider("parent"),
                resolver.resolve(scope, Optional.of(thread.id()), ExecutionOverrides.empty())
                        .provider());
        defaults.update(
                identity("workspace-default", 0), Optional.of(workspace.id()), Optional.empty(), model("child"));
        assertEquals(
                provider("child"),
                resolver.resolve(scope, Optional.of(thread.id()), ExecutionOverrides.empty())
                        .provider());
        defaults.update(
                identity("thread-default", 0), Optional.of(workspace.id()), Optional.of(thread.id()), model("spawn"));
        assertEquals(
                provider("spawn"),
                resolver.resolve(scope, Optional.of(thread.id()), ExecutionOverrides.empty())
                        .provider());
        assertEquals(
                provider("locked"),
                resolver.resolve(scope, Optional.of(thread.id()), model("locked"))
                        .provider());
        assertEquals(
                "default",
                resolver.resolve(scope, Optional.of(thread.id()), model("locked"))
                        .role()
                        .id());
    }

    @Test
    void fixedRoleWinsModelAndReasoningAndRecordsItsSource() {
        AgentRole role =
                role("fixed", Optional.of(new ModelPreference(provider("locked"))), CapabilityNarrowing.inherit());
        var resolved = resolver.resolve(scope, Optional.empty(), selection(role, "spawn"));
        assertEquals(provider("locked"), resolved.provider());
        assertEquals(Optional.of(ReasoningPreference.HIGH), resolved.reasoning());
        assertTrue(resolved.freeze("a".repeat(64), "b".repeat(64)).summary().modelLocked());
        assertTrue(resolved.provenance().stream()
                .anyMatch(value -> value.field().equals("provider")
                        && value.source() == ConfigurationSource.ROLE
                        && value.sourceId().equals("fixed")));
        roles.archive(identity("archive", 1), role.id());
        assertThrows(
                PersistenceException.class, () -> resolver.resolve(scope, Optional.empty(), selection(role, "spawn")));
        assertEquals(role, roles.require(role.id(), 1));
    }

    @Test
    void childModelPrecedenceNeverWeakensParentCapabilitiesApprovalsOrBudget() {
        AgentRole child = role(
                "child-role",
                Optional.empty(),
                new CapabilityNarrowing(Optional.empty(), Optional.of(Set.of("b", "c"))));
        var parent = resolver.resolve(scope, Optional.empty(), model("parent"));
        var parentConfig = new com.javaclaw.api.ResolvedTurnConfig(
                parent.freeze("a".repeat(64), "b".repeat(64)).role(),
                parent.provider(),
                parent.permissionProfile(),
                ApprovalPolicy.EVERY_CALL,
                budget(100),
                Set.of("tool_search"),
                Optional.empty(),
                PermissionConstraint.READ_ONLY,
                Optional.of(Set.of("a", "b")),
                "a".repeat(64),
                "b".repeat(64),
                parent.provenance());
        var inherited = resolver.resolveChild(
                scope, parentConfig, roleOnly(child), ExecutionOverrides.empty(), parent.effectivePermissions());
        assertEquals(provider("parent"), inherited.provider());
        var defaulted = resolver.resolveChild(
                scope, parentConfig, roleOnly(child), model("child"), parent.effectivePermissions());
        assertEquals(provider("child"), defaulted.provider());
        var explicit = resolver.resolveChild(
                scope, parentConfig, selection(child, "spawn"), model("child"), parent.effectivePermissions());
        assertEquals(provider("spawn"), explicit.provider());
        assertEquals(ApprovalPolicy.EVERY_CALL, explicit.approvalPolicy());
        assertEquals(budget(100), explicit.budget());
        assertEquals(PermissionConstraint.READ_ONLY, explicit.permissionConstraint());
        assertEquals(Optional.of(Set.of("b")), explicit.effectiveSkills());
        assertEquals(
                Set.of("tool_search"), explicit.effectivePermissions().tools().allowedTools());
        assertTrue(explicit.effectivePermissions().files().writeRoots().isEmpty());
        assertTrue(explicit.effectivePermissions().processes().executables().isEmpty());
    }

    @Test
    void roleCannotResurrectHiddenToolsAndEmptySelectionDisablesAll() {
        AgentRole role = role(
                "narrow",
                Optional.empty(),
                new CapabilityNarrowing(Optional.of(Set.of("tool_search", "invented")), Optional.empty()));
        var selected = resolver.resolve(scope, Optional.empty(), roleOnly(role));
        assertEquals(
                Set.of("tool_search"), selected.effectivePermissions().tools().allowedTools());
        ExecutionOverrides empty = new ExecutionOverrides(
                Optional.of(role.ref()),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.of(Set.of()),
                Optional.empty());
        assertTrue(resolver.resolve(scope, Optional.empty(), empty)
                .effectivePermissions()
                .tools()
                .allowedTools()
                .isEmpty());
        assertFalse(selected.freeze("a".repeat(64), "b".repeat(64)).summary().modelLocked());
    }

    @Test
    void crossWorkspaceThreadAndDisabledRoleAreRejectedBeforeModelUse() {
        Workspace other = core.createWorkspace(identity("other", 0), "Other", directory.resolve("other"));
        var thread = core.createThread(
                identity("foreign-thread", 0), other.id(), Optional.empty(), ThreadExecutionIntent.WORKSPACE, "Other");
        assertThrows(
                PersistenceException.class, () -> resolver.resolve(scope, Optional.of(thread.id()), model("parent")));
        AgentRole role = role("disabled", Optional.empty(), CapabilityNarrowing.inherit());
        roles.update(identity("disable", 1), role.id(), role.spec(), RoleLifecycle.DISABLED);
        assertThrows(PersistenceException.class, () -> resolver.resolve(scope, Optional.empty(), roleOnly(role)));
    }

    @Test
    void persistedSubagentDefaultsStaySeparateAndOnlyAffectNewChildResolution() {
        AgentRole child = role("configured-child", Optional.empty(), CapabilityNarrowing.inherit());
        var parent = resolver.resolve(scope, Optional.empty(), model("parent"));
        var frozen = parent.freeze("a".repeat(64), "b".repeat(64));
        defaults.updateSubagentDefaults(
                identity("subagent-install", 0), Optional.empty(), Optional.empty(), model("child"));
        assertEquals(
                provider("parent"),
                resolver.resolve(scope, Optional.empty(), ExecutionOverrides.empty())
                        .provider());
        assertEquals(
                provider("child"),
                resolver.resolveChild(scope, frozen, roleOnly(child), parent.effectivePermissions())
                        .provider());
        defaults.updateSubagentDefaults(
                identity("subagent-workspace", 0), Optional.of(workspace.id()), Optional.empty(), model("spawn"));
        var result = resolver.resolveChild(scope, frozen, roleOnly(child), parent.effectivePermissions());
        assertEquals(provider("spawn"), result.provider());
        assertTrue(result.provenance().stream()
                .anyMatch(value -> value.source() == ConfigurationSource.WORKSPACE
                        && value.sourceId().startsWith("subagent:")
                        && value.revision() == 1));
        assertEquals(
                provider("locked"),
                resolver.resolveChild(scope, frozen, selection(child, "locked"), parent.effectivePermissions())
                        .provider());
        assertThrows(
                PersistenceException.class,
                () -> defaults.updateSubagentDefaults(
                        identity("bad-subagent", 1), Optional.of(workspace.id()), Optional.empty(), roleOnly(child)));
        assertEquals(provider("parent"), frozen.provider());
    }

    private AgentRole role(String id, Optional<ModelPreference> model, CapabilityNarrowing narrowing) {
        return roles.create(
                identity("role-" + id, 0),
                id,
                new AgentRoleSpec(
                        id,
                        "测试角色",
                        "仅执行当前明确任务。",
                        model,
                        Optional.of(ReasoningPreference.HIGH),
                        narrowing,
                        PermissionConstraint.INHERIT,
                        Map.of()));
    }

    private static ExecutionOverrides selection(AgentRole role, String model) {
        return new ExecutionOverrides(
                Optional.of(role.ref()),
                Optional.of(provider(model)),
                Optional.empty(),
                Optional.of(ApprovalPolicy.NONE),
                Optional.of(budget(1_000)),
                Optional.of(Set.of("tool_search", "invented")),
                Optional.of(ReasoningPreference.LOW));
    }

    private static ExecutionOverrides roleOnly(AgentRole role) {
        return new ExecutionOverrides(
                Optional.of(role.ref()),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty());
    }

    private static ExecutionOverrides model(String model) {
        return new ExecutionOverrides(
                Optional.empty(),
                Optional.of(provider(model)),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty());
    }

    private static ProviderRef provider(String model) {
        return new ProviderRef("models", 1, model);
    }

    private static TurnBudget budget(long tokens) {
        return new TurnBudget(tokens, tokens, 4, 2, Duration.ofMinutes(1));
    }

    private CommandIdentity identity(String key, long revision) {
        return new CommandIdentity(
                "test/resolver", key, revision, json.encode(Map.of("key", key)).sha256());
    }
}
