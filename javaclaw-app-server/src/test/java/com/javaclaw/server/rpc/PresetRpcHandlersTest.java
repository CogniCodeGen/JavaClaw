package com.javaclaw.server.rpc;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.javaclaw.api.PermissionPresetInstantiationRequest;
import com.javaclaw.api.PermissionPresetInstantiationResult;
import com.javaclaw.api.PermissionPresetPreview;
import com.javaclaw.api.Workspace;
import com.javaclaw.protocol.CanonicalJson;
import com.javaclaw.protocol.CoreRpcContracts;
import com.javaclaw.protocol.PermissionProfileRpcContracts;
import com.javaclaw.protocol.ProviderProfileRpcContracts;
import com.javaclaw.protocol.SessionSecretChannel;
import com.javaclaw.protocol.WriteCommand;
import com.javaclaw.server.persistence.CommandIdentity;
import com.javaclaw.server.persistence.CoreCommandService;
import com.javaclaw.server.persistence.H2Database;
import com.javaclaw.server.persistence.PermissionProfileService;
import com.javaclaw.server.profile.ProfilePresetCatalog;
import com.javaclaw.server.security.PermissionPresetCatalog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PresetRpcHandlersTest {
    private static final Instant NOW = Instant.parse("2026-09-01T00:00:00Z");

    @TempDir
    Path temporaryDirectory;

    private CanonicalJson json;
    private CoreCommandService core;
    private RpcRouter router;
    private Workspace workspace;

    @BeforeEach
    void initializeRoutes() {
        H2Database database = new H2Database(temporaryDirectory.resolve("data-v5"));
        database.initialize();
        json = new CanonicalJson();
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        core = new CoreCommandService(database, json, clock);
        PermissionProfileService profiles = new PermissionProfileService(database, json, clock);
        workspace = createWorkspace(core);
        RpcRouter.Builder routes = RpcRouter.builder();
        new ProfilePresetRpcHandlers(new ProfilePresetCatalog(), json).register(routes);
        new PermissionPresetRpcHandlers(core, profiles, new PermissionPresetCatalog(), json).register(routes);
        router = routes.build();
    }

    @Test
    void exposesReviewedProfilePresets() throws Exception {
        try (SessionSecretChannel secrets = SessionSecretChannel.open()) {
            var result = json.decode(
                    router.route("profile/preset/list", json.parse("{}"), secrets),
                    ProviderProfileRpcContracts.AgentProfilePresetListResult.class);

            assertEquals(
                    java.util.List.of("default", "worker", "explorer"),
                    result.presets().stream().map(preset -> preset.id()).toList());
            assertTrue(
                    result.presets().stream().allMatch(preset -> preset.digest().matches("[0-9a-f]{64}")));
        }
    }

    @Test
    void previewsAndIdempotentlyInstantiatesWorkspacePermissionPreset() throws Exception {
        PermissionPresetInstantiationRequest request = new PermissionPresetInstantiationRequest(
                "workspace-review", 1, workspace.id(), "review-profile", Set.of("tool_search"), Set.of());
        try (SessionSecretChannel secrets = SessionSecretChannel.open()) {
            PermissionPresetPreview preview = json.decode(
                    router.route(
                            "permissionProfile/preset/preview",
                            json.encode(new PermissionProfileRpcContracts.PresetPreviewPayload(request)),
                            secrets),
                    PermissionPresetPreview.class);
            var params = json.encode(new WriteCommand(
                    "preset-key", 0, json.encode(new PermissionProfileRpcContracts.PresetInstantiatePayload(request))));
            PermissionPresetInstantiationResult first = json.decode(
                    router.route("permissionProfile/preset/instantiate", params, secrets),
                    PermissionPresetInstantiationResult.class);
            PermissionPresetInstantiationResult replay = json.decode(
                    router.route("permissionProfile/preset/instantiate", params, secrets),
                    PermissionPresetInstantiationResult.class);

            assertEquals(preview.proposedProfile(), first.profile());
            assertEquals(first, replay);
        }
    }

    @Test
    void archivedWorkspaceCannotPreviewOrInstantiatePermissionPreset() throws Exception {
        core.archiveWorkspace(
                CommandIdentity.from(
                        "workspace/archive",
                        new WriteCommand("archive-workspace", workspace.revision(), json.encode(Map.of())),
                        json),
                workspace.id());
        PermissionPresetInstantiationRequest request = new PermissionPresetInstantiationRequest(
                "workspace-review", 1, workspace.id(), "review-profile", Set.of(), Set.of());

        try (SessionSecretChannel secrets = SessionSecretChannel.open()) {
            assertThrows(
                    com.javaclaw.server.persistence.PersistenceException.class,
                    () -> router.route(
                            "permissionProfile/preset/preview",
                            json.encode(new PermissionProfileRpcContracts.PresetPreviewPayload(request)),
                            secrets));
            var params = json.encode(new WriteCommand(
                    "instantiate-archived",
                    0,
                    json.encode(new PermissionProfileRpcContracts.PresetInstantiatePayload(request))));
            assertThrows(
                    com.javaclaw.server.persistence.PersistenceException.class,
                    () -> router.route("permissionProfile/preset/instantiate", params, secrets));
        }
    }

    private Workspace createWorkspace(CoreCommandService core) {
        CoreRpcContracts.WorkspaceCreatePayload payload =
                new CoreRpcContracts.WorkspaceCreatePayload("Preset RPC", temporaryDirectory.resolve("workspace"));
        return core.createWorkspace(
                CommandIdentity.from("workspace/create", new WriteCommand("workspace", 0, json.encode(payload)), json),
                payload.name(),
                payload.root());
    }
}
