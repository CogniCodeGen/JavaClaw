package com.javaclaw.server.rpc;

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

import com.javaclaw.api.ApprovalRequirement;
import com.javaclaw.api.EffectivePermissionPreview;
import com.javaclaw.api.FilePermission;
import com.javaclaw.api.NetworkPermission;
import com.javaclaw.api.PermissionProfile;
import com.javaclaw.api.PermissionProfileDiff;
import com.javaclaw.api.PermissionProfileRef;
import com.javaclaw.api.ProcessPermission;
import com.javaclaw.api.ResourceLimits;
import com.javaclaw.api.ToolPermission;
import com.javaclaw.api.ToolRisk;
import com.javaclaw.api.Workspace;
import com.javaclaw.protocol.CanonicalJson;
import com.javaclaw.protocol.CoreRpcContracts;
import com.javaclaw.protocol.PermissionProfileRpcContracts;
import com.javaclaw.protocol.SessionSecretChannel;
import com.javaclaw.protocol.WriteCommand;
import com.javaclaw.server.persistence.CommandIdentity;
import com.javaclaw.server.persistence.CoreCommandService;
import com.javaclaw.server.persistence.H2Database;
import com.javaclaw.server.persistence.PermissionProfileService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class PermissionProfileRpcHandlersTest {
    private static final Instant NOW = Instant.parse("2026-09-01T00:00:00Z");

    @TempDir
    Path temporaryDirectory;

    private CanonicalJson json;
    private RpcRouter router;
    private Workspace workspace;

    @BeforeEach
    void initializeRegistrar() {
        H2Database database = new H2Database(temporaryDirectory.resolve("data-v6"));
        database.initialize();
        json = new CanonicalJson();
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        PermissionProfileService profiles = new PermissionProfileService(database, json, clock);
        profiles.installStandardProfile();
        CoreCommandService core = new CoreCommandService(database, json, clock);
        workspace = createWorkspace(core);
        RpcRouter.Builder routes = RpcRouter.builder();
        new PermissionProfileRpcHandlers(core, profiles, json).register(routes);
        router = routes.build();
    }

    @Test
    void registrar暴露七个强类型方法并贯通管理闭环() throws Exception {
        assertEquals(7, router.implementedMethods().size());
        try (SessionSecretChannel secrets = SessionSecretChannel.open()) {
            PermissionProfile standard = list(secrets).getFirst();
            assertEquals(standard, read(standard, secrets));
            PermissionProfile clone = cloneProfile(standard, secrets);
            PermissionProfile updated = update(clone, secrets);
            assertEquals(List.of(clone, updated), history(updated.id(), secrets));
            PermissionProfileDiff diff = diff(updated.id(), secrets);
            assertFalse(diff.changedSections().isEmpty());
            EffectivePermissionPreview preview = preview(updated, secrets);
            assertEquals(
                    updated.tools().allowedTools(), preview.effective().tools().allowedTools());
        }
    }

    private List<PermissionProfile> list(SessionSecretChannel secrets) throws Exception {
        return json.decode(
                        route("permissionProfile/list", json.parse("{}"), secrets),
                        PermissionProfileRpcContracts.ListResult.class)
                .profiles();
    }

    private PermissionProfile read(PermissionProfile profile, SessionSecretChannel secrets) throws Exception {
        return json.decode(
                route(
                        "permissionProfile/read",
                        json.encode(new PermissionProfileRpcContracts.ReadPayload(
                                new PermissionProfileRef(profile.id(), profile.version()))),
                        secrets),
                PermissionProfile.class);
    }

    private PermissionProfile cloneProfile(PermissionProfile standard, SessionSecretChannel secrets) throws Exception {
        PermissionProfileRpcContracts.ClonePayload payload = new PermissionProfileRpcContracts.ClonePayload(
                new PermissionProfileRef(standard.id(), standard.version()), "developer");
        return json.decode(
                route("permissionProfile/clone", command("clone", 0, payload), secrets), PermissionProfile.class);
    }

    private PermissionProfile update(PermissionProfile clone, SessionSecretChannel secrets) throws Exception {
        PermissionProfile updated = profile(clone.id(), 2);
        return json.decode(
                route(
                        "permissionProfile/update",
                        command("update", 1, new PermissionProfileRpcContracts.UpdatePayload(updated)),
                        secrets),
                PermissionProfile.class);
    }

    private List<PermissionProfile> history(String id, SessionSecretChannel secrets) throws Exception {
        return json.decode(
                        route(
                                "permissionProfile/history",
                                json.encode(new PermissionProfileRpcContracts.HistoryPayload(id)),
                                secrets),
                        PermissionProfileRpcContracts.HistoryResult.class)
                .profiles();
    }

    private PermissionProfileDiff diff(String id, SessionSecretChannel secrets) throws Exception {
        return json.decode(
                route(
                        "permissionProfile/diff",
                        json.encode(new PermissionProfileRpcContracts.DiffPayload(id, 1, 2)),
                        secrets),
                PermissionProfileDiff.class);
    }

    private EffectivePermissionPreview preview(PermissionProfile profile, SessionSecretChannel secrets)
            throws Exception {
        PermissionProfileRpcContracts.EffectivePreviewPayload payload =
                new PermissionProfileRpcContracts.EffectivePreviewPayload(
                        workspace.id(),
                        new PermissionProfileRef(profile.id(), profile.version()),
                        Optional.empty(),
                        Optional.empty());
        return json.decode(
                route("permissionProfile/effectivePreview", json.encode(payload), secrets),
                EffectivePermissionPreview.class);
    }

    private Workspace createWorkspace(CoreCommandService core) {
        CoreRpcContracts.WorkspaceCreatePayload payload =
                new CoreRpcContracts.WorkspaceCreatePayload("Permission RPC", temporaryDirectory.resolve("workspace"));
        return core.createWorkspace(
                CommandIdentity.from("workspace/create", new WriteCommand("workspace", 0, json.encode(payload)), json),
                payload.name(),
                payload.root());
    }

    private PermissionProfile profile(String id, long version) {
        Path root = temporaryDirectory.resolve("workspace");
        return new PermissionProfile(
                id,
                version,
                new FilePermission(List.of(root), List.of(root), false, false),
                new NetworkPermission(Set.of("example.invalid"), Set.of(443), true),
                new ProcessPermission(Set.of("git"), false, Duration.ofSeconds(5)),
                new ToolPermission(Set.of("tool_search"), ToolRisk.READ_ONLY, ApprovalRequirement.RISKY),
                new ResourceLimits(8L * 1024 * 1024, 1024 * 1024, 2, 8));
    }

    private com.javaclaw.api.CanonicalPayload command(String key, long expectedRevision, Object payload) {
        return json.encode(new WriteCommand(key, expectedRevision, json.encode(payload)));
    }

    private com.javaclaw.api.CanonicalPayload route(
            String method, com.javaclaw.api.CanonicalPayload params, SessionSecretChannel secrets) throws Exception {
        return router.route(method, params, secrets);
    }
}
