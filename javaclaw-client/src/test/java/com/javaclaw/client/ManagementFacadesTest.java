package com.javaclaw.client;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.javaclaw.api.AttachmentMetadata;
import com.javaclaw.api.CanonicalPayload;
import com.javaclaw.api.McpAuthType;
import com.javaclaw.api.McpCacheScope;
import com.javaclaw.api.McpCatalogEntry;
import com.javaclaw.api.McpCatalogKind;
import com.javaclaw.api.McpCatalogPage;
import com.javaclaw.api.McpCatalogRefresh;
import com.javaclaw.api.McpCatalogRefreshState;
import com.javaclaw.api.McpEndpoint;
import com.javaclaw.api.McpEndpointSpec;
import com.javaclaw.api.McpEndpointState;
import com.javaclaw.api.McpHealth;
import com.javaclaw.api.McpHealthState;
import com.javaclaw.api.McpOAuthAuthorization;
import com.javaclaw.api.McpOAuthState;
import com.javaclaw.api.McpPromptDescriptor;
import com.javaclaw.api.McpPromptMessage;
import com.javaclaw.api.McpPromptPage;
import com.javaclaw.api.McpPromptResult;
import com.javaclaw.api.McpResourceContent;
import com.javaclaw.api.McpResourceDescriptor;
import com.javaclaw.api.McpResourcePage;
import com.javaclaw.api.McpResourceReadResult;
import com.javaclaw.api.McpSamplingRole;
import com.javaclaw.api.McpTransport;
import com.javaclaw.api.WorkspaceId;
import com.javaclaw.client.facade.ExtensionBundleClient;
import com.javaclaw.client.facade.McpClient;
import com.javaclaw.client.testkit.ScriptedRpcConnection;
import com.javaclaw.protocol.BundleRpcContracts;
import com.javaclaw.protocol.CanonicalJson;
import com.javaclaw.protocol.JsonRpcRequest;
import com.javaclaw.protocol.JsonRpcResponse;
import com.javaclaw.protocol.McpRpcContracts;
import com.javaclaw.protocol.WriteCommand;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ManagementFacadesTest {
    private static final CanonicalJson JSON = new CanonicalJson();
    private static final Instant NOW = Instant.parse("2026-09-01T00:00:00Z");
    private static final String DIGEST = "a".repeat(64);
    private static final String FINGERPRINT = "b".repeat(64);
    private static final WorkspaceId WORKSPACE = WorkspaceId.parse("114fdfd7-d4e7-42f4-9563-dd552b61f97d");
    private static final CommandOptions CREATE = new CommandOptions("create", 0);
    private static final CommandOptions UPDATE = new CommandOptions("update", 2);

    @Test
    void mcpFacadePreservesEndpointCatalogAndOAuthWireIdentity() throws Exception {
        McpScript script = new McpScript();
        try (RpcClientConnection connection =
                new RpcClientConnection(new ScriptedRpcConnection(script::respond), JSON, ignored -> {})) {
            McpClient client = new McpClient(connection);
            McpEndpoint endpoint = mcpEndpoint();
            McpRpcContracts.SignedBundleRegisterPayload bundle = new McpRpcContracts.SignedBundleRegisterPayload(
                    endpoint.id(), WORKSPACE, "signed-bundle", "Signed MCP", Duration.ofSeconds(10));

            assertEquals(List.of(endpoint), client.list(WORKSPACE));
            assertEquals(endpoint, client.read(endpoint.id()));
            assertEquals(List.of(endpoint), client.history(endpoint.id()));
            assertEquals(endpoint, client.create(endpoint.id(), endpoint.spec(), CREATE));
            assertEquals(endpoint, client.update(endpoint.id(), endpoint.spec(), UPDATE));
            assertEquals(endpoint, client.registerSignedBundle(bundle, CREATE));
            assertEquals(endpoint, client.enable(endpoint.id(), UPDATE));
            assertEquals(endpoint, client.disable(endpoint.id(), UPDATE));
            assertEquals(mcpHealth(), client.health(endpoint.id()));
            assertEquals(mcpHealth(), client.probe(endpoint.id()));
            assertEquals(endpoint, client.refreshCatalog(endpoint.id(), UPDATE));
            assertEquals(Optional.of(mcpRefresh()), client.catalogRefresh(endpoint.id()));
            assertEquals(
                    mcpCatalog(),
                    client.catalog(endpoint.id(), Optional.of(McpCatalogKind.TOOL), Optional.of("4"), 20));
            assertEquals(mcpResources(), client.resources(endpoint.id(), Optional.empty()));
            assertEquals(mcpResource(), client.readResource(endpoint.id(), "docs://guide"));
            assertEquals(mcpPrompts(), client.prompts(endpoint.id(), Optional.empty()));
            assertEquals(mcpPrompt(), client.getPrompt(endpoint.id(), "review", Map.of("topic", "v5")));
            assertEquals(mcpOAuth(), client.oauth("oauth-flow"));
            assertEquals(Optional.of(mcpOAuth()), client.latestOAuth(endpoint.id()));
            assertEquals(Optional.empty(), client.latestOAuth("without-oauth"));
            assertEquals(mcpOAuth(), client.startOAuth(endpoint.id(), CREATE));
            assertEquals(mcpOAuth(), client.cancelOAuth("oauth-flow", UPDATE));
            assertThrows(IllegalArgumentException.class, () -> client.oauth("without-oauth"));
        }

        assertEquals(23, script.methods.size());
        assertEquals(List.of(CREATE, UPDATE, CREATE, UPDATE, UPDATE, UPDATE, CREATE, UPDATE), script.commands);
    }

    @Test
    void bundleFacadeMapsStagingLifecycleTrashAndTrustCommands() throws Exception {
        BundleScript script = new BundleScript();
        try (RpcClientConnection connection =
                new RpcClientConnection(new ScriptedRpcConnection(script::respond), JSON, ignored -> {})) {
            ExtensionBundleClient client = new ExtensionBundleClient(connection);
            AttachmentMetadata attachment =
                    new AttachmentMetadata(DIGEST, BundleRpcContracts.BUNDLE_MEDIA_TYPE, 10, NOW);
            AttachmentMetadata publicKey =
                    new AttachmentMetadata(DIGEST, BundleRpcContracts.PUBLIC_KEY_MEDIA_TYPE, 44, NOW);

            assertEquals(List.of(bundle()), client.list());
            assertEquals(bundle(), client.read(bundle().id()));
            assertEquals(staging(), client.stage(attachment, CREATE));
            assertEquals(bundle(), client.install(staging(), CREATE));
            assertEquals(bundle(), client.upgrade(staging(), UPDATE));
            assertEquals(bundle(), client.probe(bundle().id(), UPDATE));
            assertEquals(bundle(), client.enable(bundle().id(), UPDATE));
            assertEquals(bundle(), client.disable(bundle().id(), UPDATE));
            assertEquals(trash(), client.uninstall(bundle().id(), UPDATE));
            assertEquals(List.of(trash()), client.listTrash());
            assertEquals(trash(), client.readTrash(trash().trashId()));
            assertEquals(bundle(), client.restoreTrash(trash().trashId(), UPDATE));
            assertEquals(trash(), client.purgeTrash(trash().trashId(), "PURGE " + trash().trashId(), UPDATE));
            assertEquals(List.of(trustKey()), client.listTrustKeys());
            assertEquals(trustKey(), client.readTrustKey(trustKey().id()));
            assertEquals(trustKey(), client.importTrustKey(trustKey().id(), publicKey, CREATE));
            assertEquals(trustKey(), client.revokeTrustKey(trustKey().id(), UPDATE));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> client.purgeTrash(trash().trashId(), "PURGE another", UPDATE));
        }

        assertEquals(17, script.methods.size());
        assertTrue(script.methods.contains("extension/bundle/upgrade"));
        assertTrue(script.methods.contains("extension/bundle/trash/purge"));
        assertTrue(script.methods.contains("extension/trustKey/import"));
    }

    private static McpEndpoint mcpEndpoint() {
        McpEndpointSpec spec = new McpEndpointSpec(
                WORKSPACE,
                "Remote MCP",
                McpTransport.STREAMABLE_HTTPS,
                Optional.of(URI.create("https://mcp.example.test/api")),
                Optional.empty(),
                McpAuthType.NONE,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Duration.ofSeconds(30));
        return new McpEndpoint("mcp-endpoint", 2, McpEndpointState.ENABLED, 3, spec, NOW, NOW);
    }

    private static McpHealth mcpHealth() {
        return new McpHealth(
                "mcp-endpoint", 2, McpHealthState.HEALTHY, Optional.of("2026-07-28"), Optional.empty(), NOW);
    }

    private static McpCatalogRefresh mcpRefresh() {
        return new McpCatalogRefresh(
                "mcp-endpoint", 2, McpCatalogRefreshState.COMPLETED, 1, 1, Optional.of(3L), Optional.empty(), NOW, NOW);
    }

    private static McpCatalogPage mcpCatalog() {
        CanonicalPayload schema = new CanonicalPayload("{\"type\":\"object\"}");
        return new McpCatalogPage(
                List.of(McpCatalogEntry.tool("search", Optional.of("Search"), "Search records", schema, schema)),
                Optional.empty());
    }

    private static McpResourcePage mcpResources() {
        return new McpResourcePage(
                List.of(new McpResourceDescriptor(
                        "guide",
                        "docs://guide",
                        Optional.empty(),
                        Optional.empty(),
                        Optional.of("text/plain"),
                        Optional.of(5L))),
                Optional.empty(),
                McpCacheScope.PRIVATE,
                0,
                List.of());
    }

    private static McpResourceReadResult mcpResource() {
        return new McpResourceReadResult(
                List.of(new McpResourceContent(
                        "docs://guide", Optional.of("text/plain"), Optional.of("hello"), Optional.empty())),
                McpCacheScope.PRIVATE,
                0,
                List.of());
    }

    private static McpPromptPage mcpPrompts() {
        return new McpPromptPage(
                List.of(new McpPromptDescriptor("review", Optional.empty(), Optional.empty(), List.of())),
                Optional.empty(),
                McpCacheScope.PRIVATE,
                0,
                List.of());
    }

    private static McpPromptResult mcpPrompt() {
        return new McpPromptResult(
                Optional.empty(),
                List.of(new McpPromptMessage(
                        McpSamplingRole.USER, new CanonicalPayload("{\"text\":\"review\",\"type\":\"text\"}"))),
                List.of());
    }

    private static McpOAuthAuthorization mcpOAuth() {
        return new McpOAuthAuthorization(
                "oauth-flow",
                "mcp-endpoint",
                2,
                "auth.example.test",
                McpOAuthState.PENDING,
                NOW.plusSeconds(600),
                Optional.of("WAITING_CALLBACK"),
                NOW);
    }

    private static BundleRpcContracts.StageResult staging() {
        BundleRpcContracts.AttachmentPointer pointer = new BundleRpcContracts.AttachmentPointer(DIGEST, DIGEST);
        return new BundleRpcContracts.StageResult(
                DIGEST,
                pointer,
                DIGEST,
                "demo.extension",
                "Demo",
                "5.0.0",
                "release-key",
                FINGERPRINT,
                Set.of("QUERY"),
                permissions());
    }

    private static BundleRpcContracts.PermissionReview permissions() {
        return new BundleRpcContracts.PermissionReview(
                true,
                false,
                false,
                Set.of(),
                Set.of(),
                true,
                "worker",
                Duration.ofSeconds(10),
                64L * 1024 * 1024,
                64L * 1024,
                1,
                16);
    }

    private static BundleRpcContracts.Bundle bundle() {
        return new BundleRpcContracts.Bundle(
                "demo.extension",
                "Demo",
                "5.0.0",
                2,
                "ENABLED",
                DIGEST,
                "release-key",
                FINGERPRINT,
                Set.of("QUERY"),
                permissions(),
                new BundleRpcContracts.Health(
                        BundleRpcContracts.HealthState.HEALTHY,
                        0,
                        Optional.of(NOW),
                        Optional.empty(),
                        Optional.empty()));
    }

    private static BundleRpcContracts.TrustKey trustKey() {
        return new BundleRpcContracts.TrustKey(
                "release-key", FINGERPRINT, DIGEST, 2, BundleRpcContracts.TrustState.ACTIVE, NOW, NOW);
    }

    private static BundleRpcContracts.TrashEntry trash() {
        return new BundleRpcContracts.TrashEntry(
                "trash-entry",
                bundle().id(),
                bundle().version(),
                bundle().revision(),
                bundle().manifestDigest(),
                bundle().signingKeyId(),
                BundleRpcContracts.TrashState.TRASHED,
                NOW,
                Optional.empty(),
                Optional.empty());
    }

    private static CommandOptions command(JsonRpcRequest request) {
        WriteCommand command = JSON.decode(request.params(), WriteCommand.class);
        return new CommandOptions(command.idempotencyKey(), command.expectedRevision());
    }

    private static final class McpScript {
        private final List<String> methods = new ArrayList<>();
        private final List<CommandOptions> commands = new ArrayList<>();

        private JsonRpcResponse respond(JsonRpcRequest request) {
            methods.add(request.method());
            Object result =
                    switch (request.method()) {
                        case "mcp/endpoint/list", "mcp/endpoint/history" ->
                            new McpRpcContracts.EndpointListResult(List.of(mcpEndpoint()));
                        case "mcp/endpoint/read" -> mcpEndpoint();
                        case "mcp/endpoint/create",
                                "mcp/endpoint/update",
                                "mcp/stdio/register",
                                "mcp/endpoint/enable",
                                "mcp/endpoint/disable",
                                "mcp/catalog/refresh" -> commandResult(request, mcpEndpoint());
                        case "mcp/health/read", "mcp/health/probe" -> mcpHealth();
                        case "mcp/catalog/refresh/read" ->
                            new McpRpcContracts.CatalogRefreshResult(Optional.of(mcpRefresh()));
                        case "mcp/catalog/list" -> new McpRpcContracts.CatalogResult(mcpCatalog());
                        case "mcp/resource/list", "mcp/resource/read", "mcp/prompt/list", "mcp/prompt/get" ->
                            externalDataResult(request.method());
                        case "mcp/oauth/read" -> oauthResult(request);
                        case "mcp/oauth/start", "mcp/oauth/cancel" -> commandResult(request, mcpOAuth());
                        default -> throw new AssertionError("unexpected MCP method " + request.method());
                    };
            return JsonRpcResponse.success(request.id(), JSON.encode(result));
        }

        private Object commandResult(JsonRpcRequest request, Object result) {
            commands.add(command(request));
            return result;
        }

        private static Object externalDataResult(String method) {
            return switch (method) {
                case "mcp/resource/list" -> new McpRpcContracts.ResourcePageResult(mcpResources());
                case "mcp/resource/read" -> new McpRpcContracts.ResourceReadResult(mcpResource());
                case "mcp/prompt/list" -> new McpRpcContracts.PromptPageResult(mcpPrompts());
                case "mcp/prompt/get" -> new McpRpcContracts.PromptResult(mcpPrompt());
                default -> throw new AssertionError("unexpected MCP external data method " + method);
            };
        }

        private static McpRpcContracts.OAuthResult oauthResult(JsonRpcRequest request) {
            McpRpcContracts.OAuthQuery query = JSON.decode(request.params(), McpRpcContracts.OAuthQuery.class);
            boolean missing = query.endpointId().filter("without-oauth"::equals).isPresent()
                    || query.authorizationId().filter("without-oauth"::equals).isPresent();
            return new McpRpcContracts.OAuthResult(missing ? Optional.empty() : Optional.of(mcpOAuth()));
        }
    }

    private static final class BundleScript {
        private final List<String> methods = new ArrayList<>();

        private JsonRpcResponse respond(JsonRpcRequest request) {
            methods.add(request.method());
            Object result =
                    switch (request.method()) {
                        case "extension/bundle/list" -> new BundleRpcContracts.BundleListResult(List.of(bundle()));
                        case "extension/bundle/read" -> new BundleRpcContracts.BundleResult(bundle());
                        case "extension/bundle/stage" -> commandResult(request, staging());
                        case "extension/bundle/install",
                                "extension/bundle/upgrade",
                                "extension/bundle/health/probe",
                                "extension/bundle/enable",
                                "extension/bundle/disable",
                                "extension/bundle/trash/restore" ->
                            commandResult(request, new BundleRpcContracts.BundleResult(bundle()));
                        case "extension/bundle/uninstall", "extension/bundle/trash/purge" ->
                            commandResult(request, new BundleRpcContracts.TrashResult(trash()));
                        case "extension/bundle/trash/list" -> new BundleRpcContracts.TrashListResult(List.of(trash()));
                        case "extension/bundle/trash/read" -> new BundleRpcContracts.TrashResult(trash());
                        case "extension/trustKey/list" ->
                            new BundleRpcContracts.TrustKeyListResult(List.of(trustKey()));
                        case "extension/trustKey/read" -> new BundleRpcContracts.TrustKeyResult(trustKey());
                        case "extension/trustKey/import", "extension/trustKey/revoke" ->
                            commandResult(request, new BundleRpcContracts.TrustKeyResult(trustKey()));
                        default -> throw new AssertionError("unexpected Bundle method " + request.method());
                    };
            return JsonRpcResponse.success(request.id(), JSON.encode(result));
        }

        private static Object commandResult(JsonRpcRequest request, Object result) {
            command(request);
            return result;
        }
    }
}
