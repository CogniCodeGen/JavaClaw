package com.javaclaw.server.persistence;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.javaclaw.api.AgentRole;
import com.javaclaw.api.AgentRoleFileFormat;
import com.javaclaw.api.AgentRoleFilePreview;
import com.javaclaw.api.AgentRoleRef;
import com.javaclaw.api.AgentRoleSpec;
import com.javaclaw.api.CapabilityNarrowing;
import com.javaclaw.api.ExecutionConfiguration;
import com.javaclaw.api.ExecutionOverrides;
import com.javaclaw.api.ModelPreference;
import com.javaclaw.api.PermissionConstraint;
import com.javaclaw.api.ProviderAdapter;
import com.javaclaw.api.ProviderLifecycle;
import com.javaclaw.api.ProviderRef;
import com.javaclaw.api.RoleLifecycle;
import com.javaclaw.api.ThreadExecutionIntent;
import com.javaclaw.api.Workspace;
import com.javaclaw.protocol.CanonicalJson;
import com.javaclaw.server.ProviderEndpointTestFixtures;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentRolePersistenceTest {
    @TempDir
    Path temporaryDirectory;

    private final CanonicalJson json = new CanonicalJson();
    private final Clock clock = Clock.fixed(Instant.parse("2026-09-01T00:00:00Z"), ZoneOffset.UTC);
    private H2Database database;
    private ProviderService providers;
    private AgentRoleService roles;
    private AgentRoleFileService files;
    private CoreCommandService core;
    private ExecutionConfigurationService configurations;

    @BeforeEach
    void initialize() {
        database = new H2Database(temporaryDirectory.resolve("data-v6"));
        database.initialize();
        providers = new ProviderService(database, reference -> true, json, clock);
        roles = new AgentRoleService(database, providers, json, clock);
        files = new AgentRoleFileService(database, roles, providers, json, clock);
        core = new CoreCommandService(database, json, clock);
        configurations = new ExecutionConfigurationService(database, core, roles, json, clock);
    }

    @Test
    void builtinsInitializeAtomicallyAndCloneRevisionsSurviveRestart() {
        assertEquals(4, roles.listLatest().size());
        AgentRole builtin = roles.require("explorer", 1);
        assertThrows(
                PersistenceException.class,
                () -> roles.update(identity("update", 1, builtin), builtin.id(), builtin.spec(), RoleLifecycle.ACTIVE));
        assertThrows(
                PersistenceException.class, () -> roles.archive(identity("archive-builtin", 1, builtin), builtin.id()));
        AgentRole copy = roles.clone(identity("clone", 0, builtin), builtin.ref(), "reviewer", "Reviewer");
        assertFalse(copy.builtin());
        assertEquals(PermissionConstraint.READ_ONLY, copy.spec().permissionConstraint());
        AgentRole updated =
                roles.update(identity("update-copy", 1, copy), copy.id(), spec("Updated"), RoleLifecycle.ACTIVE);
        assertEquals(2, updated.revision());
        AgentRoleService reopened = new AgentRoleService(database, providers, json, clock);
        assertEquals(copy, reopened.require(copy.id(), 1));
        assertEquals(updated, reopened.requireAvailable(copy.id(), 2));
        AgentRole archived = reopened.archive(identity("archive-copy", 2, updated), copy.id());
        assertEquals(RoleLifecycle.ARCHIVED, archived.lifecycle());
        assertThrows(PersistenceException.class, () -> reopened.requireAvailable(copy.id(), 1));
        assertEquals(copy, reopened.require(copy.id(), 1));
    }

    @Test
    void immutableRoleWritesEnforceRevisionAndIdempotency() {
        AgentRoleSpec spec = spec("Custom");
        CommandIdentity command = identity("create", 0, spec);
        AgentRole first = roles.create(command, "custom", spec);
        assertEquals(first, roles.create(command, "custom", spec));
        assertThrows(
                PersistenceException.class,
                () -> roles.update(identity("stale", 0, spec), "custom", spec, RoleLifecycle.ACTIVE));
        assertThrows(
                PersistenceException.class,
                () -> roles.create(
                        new CommandIdentity(command.method(), command.idempotencyKey(), 0, "b".repeat(64)),
                        "custom",
                        spec));
        AgentRole disabled = roles.update(identity("disable", 1, spec), "custom", spec, RoleLifecycle.DISABLED);
        assertEquals(RoleLifecycle.DISABLED, disabled.lifecycle());
        assertThrows(PersistenceException.class, () -> roles.requireAvailable("custom", 1));
    }

    @Test
    void providerRevocationDoesNotPreventArchivalOrReplayOfCommittedRoleCommands() {
        var providerSpec = ProviderEndpointTestFixtures.chat("Provider", ProviderAdapter.OPENAI_COMPATIBLE, "model");
        providers.create(identity("provider", 0, providerSpec), "provider", providerSpec, ProviderLifecycle.ACTIVE);
        AgentRoleSpec spec = new AgentRoleSpec(
                "Locked",
                "",
                "",
                Optional.of(new ModelPreference(new ProviderRef("provider", 1, "model"))),
                Optional.empty(),
                CapabilityNarrowing.inherit(),
                PermissionConstraint.INHERIT,
                Map.of());
        CommandIdentity create = identity("create-locked", 0, spec);
        AgentRole first = roles.create(create, "locked", spec);
        ExecutionOverrides defaults = select("locked");
        CommandIdentity configure = identity("configure-locked", 0, defaults);
        ExecutionConfiguration installed =
                configurations.update(configure, Optional.empty(), Optional.empty(), defaults);
        providers.update(
                identity("disable-provider", 1, providerSpec), "provider", providerSpec, ProviderLifecycle.DISABLED);
        assertEquals(first, roles.create(create, "locked", spec));
        AgentRole archived = roles.archive(identity("archive-locked", 1, spec), "locked");
        assertEquals(RoleLifecycle.ARCHIVED, archived.lifecycle());
        assertEquals(installed, configurations.update(configure, Optional.empty(), Optional.empty(), defaults));
        assertThrows(PersistenceException.class, () -> roles.requireAvailable("locked", 1));
    }

    @Test
    void importOnlyWritesRoleAfterConfirmationAndCannotCommitOnePreviewTwice() {
        AgentRoleFilePreview preview =
                files.preview("imported", "name = 'Imported'\n", AgentRoleFileFormat.CODEX_PORTABLE);
        assertEquals(4, roles.listLatest().size());
        assertEquals(java.util.List.of("create"), preview.changedFields());
        CommandIdentity command = identity("import", 0, preview);
        AgentRole imported = files.commit(command, preview.previewId(), Optional.empty());
        assertEquals(imported, files.commit(command, preview.previewId(), Optional.empty()));
        assertThrows(
                PersistenceException.class,
                () -> files.commit(identity("again", 0, preview), preview.previewId(), Optional.empty()));
        AgentRoleFilePreview changed =
                files.preview("imported", "name = 'Renamed'\n", AgentRoleFileFormat.CODEX_PORTABLE);
        assertEquals(java.util.List.of("name"), changed.changedFields());
        assertThrows(
                PersistenceException.class,
                () -> files.commit(identity("stale-import", 0, changed), changed.previewId(), Optional.empty()));
        AgentRole updated = files.commit(identity("update-import", 1, changed), changed.previewId(), Optional.empty());
        assertEquals("Renamed", updated.spec().name());
        assertEquals(
                "imported.agent.toml",
                files.export(updated.ref(), AgentRoleFileFormat.JAVACLAW_LOSSLESS)
                        .filename());
    }

    @Test
    void unresolvedModelRequiresAnExplicitProviderBeforeCommit() {
        AgentRoleFilePreview preview =
                files.preview("model-role", "name = 'Model'\nmodel = 'unknown'\n", AgentRoleFileFormat.CODEX_PORTABLE);
        assertEquals(Optional.of("unknown"), preview.unresolvedModel());
        assertThrows(
                PersistenceException.class,
                () -> files.commit(identity("unresolved", 0, preview), preview.previewId(), Optional.empty()));
        assertThrows(PersistenceException.class, () -> roles.requireLatest("model-role"));
    }

    @Test
    void executionScopesAreIndependentVersionedAndRejectCrossWorkspaceThreads() {
        ExecutionOverrides defaults = select("default");
        ExecutionConfiguration installed = configurations.ensureInstallationDefaults(defaults);
        assertEquals(installed, configurations.ensureInstallationDefaults(select("worker")));
        Workspace first = workspace("first");
        Workspace second = workspace("second");
        var child = core.createThread(
                identity("thread", 0, "thread"),
                second.id(),
                Optional.empty(),
                ThreadExecutionIntent.WORKSPACE,
                "Thread");
        assertTrue(
                configurations.find(Optional.of(first.id()), Optional.empty()).isEmpty());
        CommandIdentity command = identity("workspace-execution", 0, defaults);
        ExecutionConfiguration configured =
                configurations.update(command, Optional.of(first.id()), Optional.empty(), defaults);
        assertEquals(configured, configurations.update(command, Optional.of(first.id()), Optional.empty(), defaults));
        assertEquals(
                installed,
                configurations.find(Optional.empty(), Optional.empty()).orElseThrow());
        assertThrows(
                PersistenceException.class,
                () -> configurations.find(Optional.of(first.id()), Optional.of(child.id())));
        assertThrows(
                PersistenceException.class,
                () -> configurations.update(
                        identity("stale-default", 0, defaults), Optional.of(first.id()), Optional.empty(), defaults));
    }

    private Workspace workspace(String name) {
        return core.createWorkspace(identity("workspace-" + name, 0, name), name, temporaryDirectory.resolve(name));
    }

    private static ExecutionOverrides select(String id) {
        return new ExecutionOverrides(
                Optional.of(new AgentRoleRef(id, 1)),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty());
    }

    private static AgentRoleSpec spec(String name) {
        return new AgentRoleSpec(
                name,
                "Description",
                "Instructions",
                Optional.empty(),
                Optional.empty(),
                CapabilityNarrowing.inherit(),
                PermissionConstraint.INHERIT,
                Map.of());
    }

    private CommandIdentity identity(String key, long revision, Object payload) {
        return new CommandIdentity(
                "agent/role/test",
                key,
                revision,
                json.encode(Map.of("payload", payload)).sha256());
    }
}
