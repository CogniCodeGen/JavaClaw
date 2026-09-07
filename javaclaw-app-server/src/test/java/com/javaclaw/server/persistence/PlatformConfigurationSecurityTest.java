package com.javaclaw.server.persistence;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.javaclaw.api.AgentRole;
import com.javaclaw.api.AgentRoleSpec;
import com.javaclaw.api.ApprovalRequirement;
import com.javaclaw.api.CapabilityNarrowing;
import com.javaclaw.api.CredentialRef;
import com.javaclaw.api.FilePermission;
import com.javaclaw.api.ModelPreference;
import com.javaclaw.api.NetworkPermission;
import com.javaclaw.api.PermissionConstraint;
import com.javaclaw.api.PermissionProfile;
import com.javaclaw.api.PermissionProfileRef;
import com.javaclaw.api.ProcessPermission;
import com.javaclaw.api.ProviderAdapter;
import com.javaclaw.api.ProviderAdapterOptions;
import com.javaclaw.api.ProviderAuthentication;
import com.javaclaw.api.ProviderEndpoint;
import com.javaclaw.api.ProviderEndpointSpec;
import com.javaclaw.api.ProviderLifecycle;
import com.javaclaw.api.ProviderRef;
import com.javaclaw.api.ResourceLimits;
import com.javaclaw.api.RoleLifecycle;
import com.javaclaw.api.ToolPermission;
import com.javaclaw.api.ToolRisk;
import com.javaclaw.api.Workspace;
import com.javaclaw.protocol.AgentRoleRpcContracts;
import com.javaclaw.protocol.CanonicalJson;
import com.javaclaw.protocol.CoreRpcContracts;
import com.javaclaw.protocol.PermissionProfileRpcContracts;
import com.javaclaw.protocol.ProviderRpcContracts;
import com.javaclaw.protocol.WriteCommand;
import com.javaclaw.server.ProviderEndpointTestFixtures;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlatformConfigurationSecurityTest {
    private static final Instant NOW = Instant.parse("2026-09-01T00:00:00Z");

    @TempDir
    Path temporaryDirectory;

    private H2Database database;
    private CanonicalJson json;
    private Clock clock;

    @BeforeEach
    void initializeDataV6() {
        database = new H2Database(temporaryDirectory.resolve("data-v6"));
        database.initialize();
        json = new CanonicalJson();
        clock = Clock.fixed(NOW, ZoneOffset.UTC);
    }

    @Test
    void permissionProfilesAreImmutableVersionedAndIdempotent() {
        PermissionProfileService service = new PermissionProfileService(database, json, clock);
        service.installStandardProfile();
        service.installStandardProfile();
        PermissionProfileRpcContracts.ClonePayload clonePayload = new PermissionProfileRpcContracts.ClonePayload(
                new PermissionProfileRef(PermissionProfileService.STANDARD_PROFILE_ID, 1), "custom");
        CommandIdentity cloneIdentity = identity("permissionProfile/clone", "profile-clone", 0, clonePayload);
        PermissionProfile clone = service.cloneProfile(cloneIdentity, clonePayload.source(), clonePayload.newId());
        assertEquals(clone, service.cloneProfile(cloneIdentity, clonePayload.source(), clonePayload.newId()));

        PermissionProfile first = profile("custom", 2, Set.of("read", "write"), ToolRisk.WORKSPACE_WRITE);
        PermissionProfileRpcContracts.UpdatePayload firstPayload =
                new PermissionProfileRpcContracts.UpdatePayload(first);
        CommandIdentity firstIdentity = identity("permissionProfile/update", "profile-1", 1, firstPayload);
        assertEquals(first, service.update(firstIdentity, first));
        assertEquals(first, service.update(firstIdentity, first));
        assertEquals(List.of(clone, first), service.history("custom"));
        assertEquals(2, service.listLatest().size());
        assertThrows(PersistenceException.class, () -> service.require("missing", 1));
        assertThrows(
                PersistenceException.class,
                () -> service.update(
                        identity("permissionProfile/update", "wrong-version", 1, firstPayload),
                        profile("custom", 3, Set.of("read"), ToolRisk.READ_ONLY)));

        PermissionProfileRpcContracts.UpdatePayload changedPayload = new PermissionProfileRpcContracts.UpdatePayload(
                profile("other", 1, Set.of("read"), ToolRisk.READ_ONLY));
        assertThrows(
                PersistenceException.class,
                () -> service.update(
                        identity("permissionProfile/update", "profile-1", 1, changedPayload),
                        changedPayload.profile()));
    }

    @Test
    void currentPermissionVersionCanOnlyNarrowFrozenTurnRights() {
        PermissionProfileService service = new PermissionProfileService(database, json, clock);
        service.installStandardProfile();
        PermissionProfileRpcContracts.ClonePayload clonePayload = new PermissionProfileRpcContracts.ClonePayload(
                new PermissionProfileRef(PermissionProfileService.STANDARD_PROFILE_ID, 1), "runtime");
        service.cloneProfile(
                identity("permissionProfile/clone", "runtime-clone", 0, clonePayload),
                clonePayload.source(),
                clonePayload.newId());
        PermissionProfile broad = profile("runtime", 2, Set.of("read", "write"), ToolRisk.WORKSPACE_WRITE);
        PermissionProfile narrow = profile("runtime", 3, Set.of("read"), ToolRisk.READ_ONLY);
        service.update(
                identity(
                        "permissionProfile/update",
                        "runtime-1",
                        1,
                        new PermissionProfileRpcContracts.UpdatePayload(broad)),
                broad);
        service.update(
                identity(
                        "permissionProfile/update",
                        "runtime-2",
                        2,
                        new PermissionProfileRpcContracts.UpdatePayload(narrow)),
                narrow);
        Workspace workspace = workspace();

        PermissionProfile effective = service.resolve("runtime", 2, workspace);

        assertEquals(Set.of("read"), effective.tools().allowedTools());
        assertEquals(ToolRisk.READ_ONLY, effective.tools().maximumRisk());
        assertTrue(effective
                .files()
                .readRoots()
                .contains(workspace.root().toAbsolutePath().normalize()));
        assertFalse(effective.files().followSymbolicLinks());
        assertThrows(
                PersistenceException.class,
                () -> service.update(
                        identity(
                                "permissionProfile/update",
                                "runtime-stale",
                                2,
                                new PermissionProfileRpcContracts.UpdatePayload(
                                        profile("runtime", 3, Set.of(), ToolRisk.READ_ONLY))),
                        profile("runtime", 3, Set.of(), ToolRisk.READ_ONLY)));
    }

    @Test
    void permissionPayloadRowIdentityAndBuiltinContentAreVerified() throws Exception {
        PermissionProfileService service = new PermissionProfileService(database, json, clock);
        service.installStandardProfile();
        PermissionProfile standard = service.require(PermissionProfileService.STANDARD_PROFILE_ID, 1);
        PermissionProfile changed = new PermissionProfile(
                standard.id(),
                standard.version(),
                standard.files(),
                standard.network(),
                standard.processes(),
                new ToolPermission(Set.of(), ToolRisk.READ_ONLY, ApprovalRequirement.NONE),
                standard.resources());
        updateProfilePayload(standard.id(), json.encode(changed).json());
        assertThrows(PersistenceException.class, service::installStandardProfile);

        PermissionProfile mismatched = profile("other-id", 1, Set.of(), ToolRisk.READ_ONLY);
        updateProfilePayload(standard.id(), json.encode(mismatched).json());
        assertThrows(PersistenceException.class, () -> service.require(standard.id(), 1));
    }

    @Test
    void providerConfigurationRejectsDirectCredentialMutationAndUsesOptimisticRevision() {
        ProviderService service = new ProviderService(database, reference -> true, json, clock);
        ProviderEndpointSpec first = providerSpec("Primary", "gpt-test");
        ProviderRpcContracts.ProviderCreatePayload firstPayload =
                new ProviderRpcContracts.ProviderCreatePayload("openai", first, ProviderLifecycle.ACTIVE);
        CommandIdentity firstIdentity = identity("provider/create", "provider-1", 0, firstPayload);

        ProviderEndpoint created = service.create(firstIdentity, "openai", first, ProviderLifecycle.ACTIVE);
        assertEquals(created, service.create(firstIdentity, "openai", first, ProviderLifecycle.ACTIVE));
        assertEquals(List.of(created), service.listLatest());

        ProviderEndpointSpec second = providerSpec("Secondary", "gpt-test");
        ProviderRpcContracts.ProviderUpdatePayload secondPayload =
                new ProviderRpcContracts.ProviderUpdatePayload("openai", second, ProviderLifecycle.ACTIVE);
        ProviderEndpoint updated = service.update(
                identity("provider/update", "provider-2", 1, secondPayload),
                "openai",
                second,
                ProviderLifecycle.ACTIVE);
        assertEquals(2, updated.revision());

        assertDirectCredentialRejected(service);

        assertThrows(
                IllegalArgumentException.class,
                () -> new ProviderEndpointSpec(
                        "Unsafe",
                        ProviderAdapter.OPENAI_COMPATIBLE,
                        Optional.empty(),
                        ProviderAuthentication.API_KEY,
                        ProviderEndpointTestFixtures.chat("Unsafe", ProviderAdapter.OPENAI_COMPATIBLE, "gpt-test")
                                .models(),
                        Optional.empty(),
                        Duration.ofSeconds(30),
                        0,
                        ProviderAdapterOptions.defaults(ProviderAdapter.ANTHROPIC)));
        assertThrows(
                PersistenceException.class,
                () -> service.update(
                        identity("provider/update", "provider-missing", 3, secondPayload),
                        "missing",
                        second,
                        ProviderLifecycle.ACTIVE));
    }

    private void assertDirectCredentialRejected(ProviderService service) {
        ProviderEndpointSpec directCredential = new ProviderEndpointSpec(
                "Direct credential",
                ProviderAdapter.OPENAI_COMPATIBLE,
                Optional.empty(),
                ProviderAuthentication.API_KEY,
                ProviderEndpointTestFixtures.chat("Direct credential", ProviderAdapter.OPENAI_COMPATIBLE, "gpt-test")
                        .models(),
                Optional.of(new CredentialRef("provider", "opaque-test-key")),
                Duration.ofSeconds(30),
                1,
                ProviderAdapterOptions.defaults(ProviderAdapter.OPENAI_COMPATIBLE));
        assertThrows(
                IllegalArgumentException.class,
                () -> new ProviderRpcContracts.ProviderCreatePayload(
                        "direct", directCredential, ProviderLifecycle.ACTIVE));
        assertThrows(
                PersistenceException.class,
                () -> service.create(
                        identity("provider/create", "provider-direct", 0, directCredential),
                        "direct",
                        directCredential,
                        ProviderLifecycle.ACTIVE));
    }

    @Test
    void agentRoleCanLockAnExactProviderWithoutCarryingPermissionGrants() {
        PermissionProfileService permissions = new PermissionProfileService(database, json, clock);
        permissions.installStandardProfile();
        ProviderService providers = new ProviderService(database, reference -> true, json, clock);
        ProviderEndpointSpec providerSpec = providerSpec("Primary", "gpt-test");
        providers.create(
                identity(
                        "provider/create",
                        "profile-provider",
                        0,
                        new ProviderRpcContracts.ProviderCreatePayload(
                                "openai", providerSpec, ProviderLifecycle.ACTIVE)),
                "openai",
                providerSpec,
                ProviderLifecycle.ACTIVE);
        AgentRoleService service = new AgentRoleService(database, providers, json, clock);
        AgentRoleSpec profileSpec = new AgentRoleSpec(
                "Developer",
                "",
                "保持变更简洁。",
                Optional.of(new ModelPreference(new ProviderRef("openai", 1, "gpt-test"))),
                Optional.empty(),
                new CapabilityNarrowing(Optional.of(Set.of("tool/search")), Optional.empty()),
                PermissionConstraint.INHERIT,
                java.util.Map.of());
        AgentRoleRpcContracts.CreatePayload command = new AgentRoleRpcContracts.CreatePayload("developer", profileSpec);
        CommandIdentity createIdentity = identity("agent/role/create", "profile-key", 0, command);

        AgentRole created = service.create(createIdentity, "developer", profileSpec);
        assertEquals(created, service.create(createIdentity, "developer", profileSpec));
        assertEquals(1, created.revision());
        assertThrows(
                PersistenceException.class,
                () -> service.create(
                        identity(
                                "agent/role/create",
                                "profile-key",
                                0,
                                new AgentRoleRpcContracts.CreatePayload("other", profileSpec)),
                        "other",
                        profileSpec));
        assertThrows(IllegalArgumentException.class, () -> service.create(createIdentity, "bad space", profileSpec));
        assertThrows(
                PersistenceException.class,
                () -> service.update(
                        identity(
                                "agent/role/update",
                                "profile-stale",
                                0,
                                new AgentRoleRpcContracts.UpdatePayload(
                                        "developer", profileSpec, RoleLifecycle.ACTIVE)),
                        "developer",
                        profileSpec,
                        RoleLifecycle.ACTIVE));
    }

    @Test
    void agentRole拒绝归档更新无效引用和幂等身份漂移() {
        PermissionProfileService permissions = new PermissionProfileService(database, json, clock);
        permissions.installStandardProfile();
        ProviderService providers = new ProviderService(database, reference -> true, json, clock);
        ProviderEndpointSpec endpointSpec = providerSpec("Profile source", "gpt-test");
        providers.create(
                identity("provider/create", "profile-source", 0, endpointSpec),
                "profile-source",
                endpointSpec,
                ProviderLifecycle.ACTIVE);
        AgentRoleService profiles = new AgentRoleService(database, providers, json, clock);
        AgentRoleSpec valid = agentProfileSpec(new ProviderRef("profile-source", 1, "gpt-test"));

        assertThrows(
                PersistenceException.class,
                () -> profiles.update(
                        identity("agent/role/update", "archive-through-update", 1, valid),
                        "profile",
                        valid,
                        RoleLifecycle.ARCHIVED));
        AgentRoleSpec missingModel = agentProfileSpec(new ProviderRef("profile-source", 1, "missing-model"));
        assertThrows(
                PersistenceException.class,
                () -> profiles.create(
                        identity("agent/role/create", "missing-model", 0, missingModel),
                        "missing-model",
                        missingModel));

        CommandIdentity create = identity("agent/role/create", "stable-profile", 0, valid);
        profiles.create(create, "stable-profile", valid);
        CommandIdentity changedMethod = new CommandIdentity(
                "profile/other", create.idempotencyKey(), create.expectedRevision(), create.requestDigest());
        assertThrows(PersistenceException.class, () -> profiles.create(changedMethod, "stable-profile", valid));
        assertThrows(
                PersistenceException.class,
                () -> profiles.create(
                        identity("agent/role/create", "missing-revision", 1, valid), "missing-revision", valid));

        CommandIdentity archive = identity("provider/archive", "archive-source", 1, endpointSpec);
        providers.archive(archive, "profile-source");
        CommandIdentity archiveMethodDrift = new CommandIdentity(
                "provider/other", archive.idempotencyKey(), archive.expectedRevision(), archive.requestDigest());
        assertThrows(PersistenceException.class, () -> providers.archive(archiveMethodDrift, "profile-source"));
        AgentRoleSpec staleActiveProvider = agentProfileSpec(new ProviderRef("profile-source", 1, "gpt-test"));
        assertThrows(
                PersistenceException.class,
                () -> profiles.create(
                        identity("agent/role/create", "stale-active-provider", 0, staleActiveProvider),
                        "stale-active-provider",
                        staleActiveProvider));
        AgentRoleSpec archivedProvider = agentProfileSpec(new ProviderRef("profile-source", 2, "gpt-test"));
        assertThrows(
                PersistenceException.class,
                () -> profiles.create(
                        identity("agent/role/create", "archived-provider", 0, archivedProvider),
                        "archived-provider",
                        archivedProvider));
    }

    private static ProviderEndpointSpec providerSpec(String name, String model) {
        ProviderEndpointSpec defaults =
                ProviderEndpointTestFixtures.chat(name, ProviderAdapter.OPENAI_COMPATIBLE, model);
        return new ProviderEndpointSpec(
                defaults.displayName(),
                defaults.adapter(),
                defaults.baseUri(),
                defaults.authentication(),
                defaults.models(),
                defaults.credential(),
                defaults.timeout(),
                1,
                defaults.options());
    }

    private static AgentRoleSpec agentProfileSpec(ProviderRef provider) {
        return new AgentRoleSpec(
                "Developer",
                "",
                "",
                Optional.of(new ModelPreference(provider)),
                Optional.empty(),
                new CapabilityNarrowing(Optional.of(Set.of()), Optional.empty()),
                PermissionConstraint.INHERIT,
                java.util.Map.of());
    }

    private PermissionProfile profile(String id, long version, Set<String> tools, ToolRisk risk) {
        Path root = temporaryDirectory.resolve("workspace").toAbsolutePath().normalize();
        return new PermissionProfile(
                id,
                version,
                new FilePermission(List.of(root), List.of(root), true, false),
                new NetworkPermission(Set.of("example.invalid"), Set.of(443), true),
                new ProcessPermission(Set.of("git"), false, Duration.ofSeconds(5)),
                new ToolPermission(tools, risk, ApprovalRequirement.RISKY),
                new ResourceLimits(8L * 1024 * 1024, 1024 * 1024, 2, 8));
    }

    private Workspace workspace() {
        CoreCommandService core = new CoreCommandService(database, json, clock);
        CoreRpcContracts.WorkspaceCreatePayload payload =
                new CoreRpcContracts.WorkspaceCreatePayload("配置测试", temporaryDirectory.resolve("workspace"));
        return core.createWorkspace(
                identity("workspace/create", "workspace", 0, payload), payload.name(), payload.root());
    }

    private CommandIdentity identity(String method, String key, long expectedRevision, Object payload) {
        return CommandIdentity.from(method, new WriteCommand(key, expectedRevision, json.encode(payload)), json);
    }

    private void updateProfilePayload(String id, String payload) throws Exception {
        try (var connection = database.open();
                var statement = connection.prepareStatement(
                        "UPDATE CORE.PERMISSION_PROFILE SET PAYLOAD = ? WHERE ID = ? AND VERSION = 1")) {
            statement.setString(1, payload);
            statement.setString(2, id);
            statement.executeUpdate();
        }
    }
}
