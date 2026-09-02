package com.javaclaw.server.persistence;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.javaclaw.api.AgentProfile;
import com.javaclaw.api.AgentProfileSpec;
import com.javaclaw.api.ApprovalRequirement;
import com.javaclaw.api.CredentialRef;
import com.javaclaw.api.FilePermission;
import com.javaclaw.api.NetworkPermission;
import com.javaclaw.api.PermissionProfile;
import com.javaclaw.api.PermissionProfileRef;
import com.javaclaw.api.ProcessPermission;
import com.javaclaw.api.ProfileLifecycle;
import com.javaclaw.api.ProviderAdapter;
import com.javaclaw.api.ProviderEndpoint;
import com.javaclaw.api.ProviderEndpointSpec;
import com.javaclaw.api.ProviderLifecycle;
import com.javaclaw.api.ProviderRef;
import com.javaclaw.api.ProviderRole;
import com.javaclaw.api.ResourceLimits;
import com.javaclaw.api.ToolPermission;
import com.javaclaw.api.ToolRisk;
import com.javaclaw.api.Workspace;
import com.javaclaw.protocol.CanonicalJson;
import com.javaclaw.protocol.CoreRpcContracts;
import com.javaclaw.protocol.PermissionProfileRpcContracts;
import com.javaclaw.protocol.ProviderProfileRpcContracts;
import com.javaclaw.protocol.WriteCommand;

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
    void initializeDataV5() {
        database = new H2Database(temporaryDirectory.resolve("data-v5"));
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
        ProviderService service = new ProviderService(database, json, clock);
        ProviderEndpointSpec first = providerSpec("Primary", "gpt-test");
        ProviderProfileRpcContracts.ProviderCreatePayload firstPayload =
                new ProviderProfileRpcContracts.ProviderCreatePayload("openai", first);
        CommandIdentity firstIdentity = identity("provider/create", "provider-1", 0, firstPayload);

        ProviderEndpoint created = service.create(firstIdentity, "openai", first);
        assertEquals(created, service.create(firstIdentity, "openai", first));
        assertEquals(List.of(created), service.listLatest());

        ProviderEndpointSpec second = providerSpec("Secondary", "gpt-test");
        ProviderProfileRpcContracts.ProviderUpdatePayload secondPayload =
                new ProviderProfileRpcContracts.ProviderUpdatePayload("openai", second, ProviderLifecycle.ACTIVE);
        ProviderEndpoint updated = service.update(
                identity("provider/update", "provider-2", 1, secondPayload),
                "openai",
                second,
                ProviderLifecycle.ACTIVE);
        assertEquals(2, updated.revision());

        ProviderEndpointSpec directCredential = new ProviderEndpointSpec(
                "Direct credential",
                ProviderAdapter.OPENAI_COMPATIBLE,
                Optional.empty(),
                Set.of(ProviderRole.CHAT),
                List.of("gpt-test"),
                Optional.of(new CredentialRef("provider", "opaque-test-key")),
                Duration.ofSeconds(30),
                1,
                Map.of());
        ProviderProfileRpcContracts.ProviderCreatePayload directPayload =
                new ProviderProfileRpcContracts.ProviderCreatePayload("direct", directCredential);
        assertThrows(
                PersistenceException.class,
                () -> service.create(
                        identity("provider/create", "provider-direct", 0, directPayload), "direct", directCredential));

        assertThrows(
                IllegalArgumentException.class,
                () -> new ProviderEndpointSpec(
                        "Unsafe",
                        ProviderAdapter.OPENAI_COMPATIBLE,
                        Optional.empty(),
                        Set.of(ProviderRole.CHAT),
                        List.of("gpt-test"),
                        Optional.empty(),
                        Duration.ofSeconds(30),
                        0,
                        Map.of("client_secret", "plain")));
        assertThrows(
                PersistenceException.class,
                () -> service.update(
                        identity("provider/update", "provider-missing", 3, secondPayload),
                        "missing",
                        second,
                        ProviderLifecycle.ACTIVE));
    }

    @Test
    void agentProfileUsesExactProviderAndPermissionReferences() {
        PermissionProfileService permissions = new PermissionProfileService(database, json, clock);
        permissions.installStandardProfile();
        ProviderService providers = new ProviderService(database, json, clock);
        ProviderEndpointSpec providerSpec = providerSpec("Primary", "gpt-test");
        providers.create(
                identity(
                        "provider/create",
                        "profile-provider",
                        0,
                        new ProviderProfileRpcContracts.ProviderCreatePayload("openai", providerSpec)),
                "openai",
                providerSpec);
        AgentProfileService service = new AgentProfileService(database, providers, permissions, json, clock);
        AgentProfileSpec profileSpec = new AgentProfileSpec(
                "Developer",
                "保持变更简洁。",
                new ProviderRef("openai", 1, "gpt-test"),
                new PermissionProfileRef(PermissionProfileService.STANDARD_PROFILE_ID, 1),
                Set.of("tool/search"),
                new com.javaclaw.api.TurnBudget(4_000, 1_000, 4, 1, Duration.ofMinutes(1)));
        ProviderProfileRpcContracts.AgentProfileCreatePayload command =
                new ProviderProfileRpcContracts.AgentProfileCreatePayload("developer", profileSpec);
        CommandIdentity createIdentity = identity("profile/create", "profile-key", 0, command);

        AgentProfile created = service.create(createIdentity, "developer", profileSpec);
        assertEquals(created, service.create(createIdentity, "developer", profileSpec));
        assertEquals(1, created.revision());
        assertThrows(
                PersistenceException.class,
                () -> service.create(
                        identity(
                                "profile/create",
                                "profile-key",
                                0,
                                new ProviderProfileRpcContracts.AgentProfileCreatePayload("other", profileSpec)),
                        "other",
                        profileSpec));
        assertThrows(IllegalArgumentException.class, () -> service.create(createIdentity, "bad space", profileSpec));
        assertThrows(
                PersistenceException.class,
                () -> service.update(
                        identity(
                                "profile/update",
                                "profile-stale",
                                0,
                                new ProviderProfileRpcContracts.AgentProfileUpdatePayload(
                                        "developer", profileSpec, ProfileLifecycle.ACTIVE)),
                        "developer",
                        profileSpec,
                        ProfileLifecycle.ACTIVE));
    }

    @Test
    void agentProfile拒绝归档更新无效引用和幂等身份漂移() {
        PermissionProfileService permissions = new PermissionProfileService(database, json, clock);
        permissions.installStandardProfile();
        ProviderService providers = new ProviderService(database, json, clock);
        ProviderEndpointSpec endpointSpec = providerSpec("Profile source", "gpt-test");
        providers.create(
                identity("provider/create", "profile-source", 0, endpointSpec), "profile-source", endpointSpec);
        AgentProfileService profiles = new AgentProfileService(database, providers, permissions, json, clock);
        AgentProfileSpec valid = agentProfileSpec(new ProviderRef("profile-source", 1, "gpt-test"));

        assertThrows(
                PersistenceException.class,
                () -> profiles.update(
                        identity("profile/update", "archive-through-update", 1, valid),
                        "profile",
                        valid,
                        ProfileLifecycle.ARCHIVED));
        AgentProfileSpec missingModel = agentProfileSpec(new ProviderRef("profile-source", 1, "missing-model"));
        assertThrows(
                PersistenceException.class,
                () -> profiles.create(
                        identity("profile/create", "missing-model", 0, missingModel), "missing-model", missingModel));

        CommandIdentity create = identity("profile/create", "stable-profile", 0, valid);
        profiles.create(create, "stable-profile", valid);
        CommandIdentity changedMethod = new CommandIdentity(
                "profile/other", create.idempotencyKey(), create.expectedRevision(), create.requestDigest());
        assertThrows(PersistenceException.class, () -> profiles.create(changedMethod, "stable-profile", valid));
        assertThrows(
                PersistenceException.class,
                () -> profiles.create(
                        identity("profile/create", "missing-revision", 1, valid), "missing-revision", valid));

        CommandIdentity archive = identity("provider/archive", "archive-source", 1, endpointSpec);
        providers.archive(archive, "profile-source");
        CommandIdentity archiveMethodDrift = new CommandIdentity(
                "provider/other", archive.idempotencyKey(), archive.expectedRevision(), archive.requestDigest());
        assertThrows(PersistenceException.class, () -> providers.archive(archiveMethodDrift, "profile-source"));
        AgentProfileSpec archivedProvider = agentProfileSpec(new ProviderRef("profile-source", 2, "gpt-test"));
        assertThrows(
                PersistenceException.class,
                () -> profiles.create(
                        identity("profile/create", "archived-provider", 0, archivedProvider),
                        "archived-provider",
                        archivedProvider));
    }

    private static ProviderEndpointSpec providerSpec(String name, String model) {
        return new ProviderEndpointSpec(
                name,
                ProviderAdapter.OPENAI_COMPATIBLE,
                Optional.empty(),
                Set.of(ProviderRole.CHAT),
                List.of(model),
                Optional.empty(),
                Duration.ofSeconds(30),
                1,
                Map.of());
    }

    private static AgentProfileSpec agentProfileSpec(ProviderRef provider) {
        return new AgentProfileSpec(
                "Developer",
                "",
                provider,
                new PermissionProfileRef(PermissionProfileService.STANDARD_PROFILE_ID, 1),
                Set.of(),
                new com.javaclaw.api.TurnBudget(4_000, 1_000, 4, 1, Duration.ofMinutes(1)));
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
