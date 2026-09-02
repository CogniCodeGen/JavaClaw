package com.javaclaw.server.rpc;

import java.net.URI;
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

import com.javaclaw.api.CanonicalPayload;
import com.javaclaw.api.PrivateNetworkGrant;
import com.javaclaw.api.PrivateNetworkGrantPreview;
import com.javaclaw.api.PrivateNetworkPurpose;
import com.javaclaw.api.ToolIdentity;
import com.javaclaw.api.UnattendedToolGrant;
import com.javaclaw.api.UnattendedToolGrantDraft;
import com.javaclaw.api.Workspace;
import com.javaclaw.protocol.CanonicalJson;
import com.javaclaw.protocol.CoreRpcContracts;
import com.javaclaw.protocol.SecurityGrantRpcContracts;
import com.javaclaw.protocol.SessionSecretChannel;
import com.javaclaw.protocol.WriteCommand;
import com.javaclaw.server.persistence.CommandIdentity;
import com.javaclaw.server.persistence.CoreCommandService;
import com.javaclaw.server.persistence.H2Database;
import com.javaclaw.server.security.grant.PrivateNetworkGrantService;
import com.javaclaw.server.security.grant.SecurityGrantAuditService;
import com.javaclaw.server.security.grant.UnattendedToolGrantService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SecurityGrantRpcHandlersTest {
    private static final Instant NOW = Instant.parse("2026-09-01T00:00:00Z");
    private static final String DIGEST = "a".repeat(64);

    @TempDir
    Path temporaryDirectory;

    private CanonicalJson json;
    private PrivateNetworkGrantService privateNetwork;
    private UnattendedToolGrantService unattended;
    private RpcRouter router;
    private Workspace workspace;

    @BeforeEach
    void 初始化独立Registrar() {
        H2Database database = new H2Database(temporaryDirectory.resolve("data-v5"));
        database.initialize();
        json = new CanonicalJson();
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        privateNetwork = new PrivateNetworkGrantService(database, json, clock);
        unattended = new UnattendedToolGrantService(database, json, clock);
        SecurityGrantAuditService audit = new SecurityGrantAuditService(database, json);
        RpcRouter.Builder routes = RpcRouter.builder();
        new SecurityGrantRpcHandlers(privateNetwork, unattended, audit, json).register(routes);
        router = routes.build();
        CoreCommandService core = new CoreCommandService(database, json, clock);
        CoreRpcContracts.WorkspaceCreatePayload payload = new CoreRpcContracts.WorkspaceCreatePayload(
                "Security RPC", temporaryDirectory.resolve("workspace").toAbsolutePath());
        workspace = core.createWorkspace(
                identity("workspace/create", "workspace", 0, payload), payload.name(), payload.root());
    }

    @Test
    void registrar暴露十个强类型管理方法并保持写信封() throws Exception {
        assertEquals(10, router.implementedMethods().size());
        try (SessionSecretChannel secrets = SessionSecretChannel.open()) {
            PrivateNetworkGrant privateGrant = exercisePrivateNetwork(secrets);
            UnattendedToolGrant unattendedGrant = exerciseUnattended(secrets);
            assertAuditEmpty(secrets);
            assertEquals(2, revokePrivate(privateGrant, secrets).revision());
            assertEquals(2, revokeUnattended(unattendedGrant, secrets).revision());
        }
    }

    private PrivateNetworkGrant exercisePrivateNetwork(SessionSecretChannel secrets) throws Exception {
        PrivateNetworkGrantPreview preview = json.decode(
                route(
                        "privateNetworkGrant/preview",
                        new SecurityGrantRpcContracts.PrivateNetworkPreviewPayload(
                                workspace.id(),
                                PrivateNetworkPurpose.MCP,
                                URI.create("https://mcp.example"),
                                Set.of("10.0.0.1"),
                                Optional.of(Duration.ofHours(1))),
                        secrets),
                PrivateNetworkGrantPreview.class);
        PrivateNetworkGrant grant = json.decode(
                route(
                        "privateNetworkGrant/create",
                        command(
                                "private-create",
                                0,
                                new SecurityGrantRpcContracts.PrivateNetworkCreatePayload(preview)),
                        secrets),
                PrivateNetworkGrant.class);
        assertEquals(
                List.of(grant),
                json.decode(
                                route(
                                        "privateNetworkGrant/list",
                                        new SecurityGrantRpcContracts.WorkspaceGrantQuery(workspace.id()),
                                        secrets),
                                SecurityGrantRpcContracts.PrivateNetworkListResult.class)
                        .grants());
        assertEquals(
                List.of(grant),
                json.decode(
                                route(
                                        "privateNetworkGrant/history",
                                        new SecurityGrantRpcContracts.GrantHistoryQuery(grant.id()),
                                        secrets),
                                SecurityGrantRpcContracts.PrivateNetworkHistoryResult.class)
                        .grants());
        return grant;
    }

    private UnattendedToolGrant exerciseUnattended(SessionSecretChannel secrets) throws Exception {
        UnattendedToolGrant grant = json.decode(
                route(
                        "unattendedToolGrant/create",
                        command("unattended-create", 0, new SecurityGrantRpcContracts.UnattendedCreatePayload(draft())),
                        secrets),
                UnattendedToolGrant.class);
        assertEquals(
                1,
                json.decode(
                                route(
                                        "unattendedToolGrant/list",
                                        new SecurityGrantRpcContracts.WorkspaceGrantQuery(workspace.id()),
                                        secrets),
                                SecurityGrantRpcContracts.UnattendedListResult.class)
                        .grants()
                        .size());
        assertEquals(
                List.of(grant),
                json.decode(
                                route(
                                        "unattendedToolGrant/history",
                                        new SecurityGrantRpcContracts.GrantHistoryQuery(grant.id()),
                                        secrets),
                                SecurityGrantRpcContracts.UnattendedHistoryResult.class)
                        .grants());
        return grant;
    }

    private void assertAuditEmpty(SessionSecretChannel secrets) throws Exception {
        assertTrue(json.decode(
                        route(
                                "permissionDecision/list",
                                new SecurityGrantRpcContracts.PermissionDecisionListPayload(
                                        workspace.id(), Optional.empty(), Optional.empty(), 10),
                                secrets),
                        SecurityGrantRpcContracts.PermissionDecisionListResult.class)
                .traces()
                .isEmpty());
    }

    private PrivateNetworkGrant revokePrivate(PrivateNetworkGrant grant, SessionSecretChannel secrets)
            throws Exception {
        return json.decode(
                route(
                        "privateNetworkGrant/revoke",
                        command("private-revoke", 1, new SecurityGrantRpcContracts.GrantRevokePayload(grant.id())),
                        secrets),
                PrivateNetworkGrant.class);
    }

    private UnattendedToolGrant revokeUnattended(UnattendedToolGrant grant, SessionSecretChannel secrets)
            throws Exception {
        return json.decode(
                route(
                        "unattendedToolGrant/revoke",
                        command("unattended-revoke", 1, new SecurityGrantRpcContracts.GrantRevokePayload(grant.id())),
                        secrets),
                UnattendedToolGrant.class);
    }

    private CanonicalPayload route(String method, Object params, SessionSecretChannel secrets) throws Exception {
        return router.route(method, json.encode(params), secrets);
    }

    private WriteCommand command(String key, long revision, Object payload) {
        return new WriteCommand(key, revision, json.encode(payload));
    }

    private UnattendedToolGrantDraft draft() {
        return new UnattendedToolGrantDraft(
                workspace.id(),
                "nightly",
                1,
                new ToolIdentity("schedule-extension", "notify", 1),
                2,
                DIGEST,
                json.parse("{\"message\":\"fixed\"}"),
                Set.of("message"),
                1,
                Duration.ofDays(1));
    }

    private CommandIdentity identity(String method, String key, long revision, Object payload) {
        return CommandIdentity.from(method, command(key, revision, payload), json);
    }
}
