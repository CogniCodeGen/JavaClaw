package com.javaclaw.desktop.settings;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import com.javaclaw.api.CanonicalPayload;
import com.javaclaw.api.CredentialClearReceipt;
import com.javaclaw.api.CredentialMetadata;
import com.javaclaw.api.CredentialRef;
import com.javaclaw.api.McpCacheScope;
import com.javaclaw.api.McpCatalogEntry;
import com.javaclaw.api.McpCatalogKind;
import com.javaclaw.api.McpCatalogPage;
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
import com.javaclaw.api.PrivateNetworkGrant;
import com.javaclaw.api.Workspace;
import com.javaclaw.api.WorkspaceId;
import com.javaclaw.client.CommandOptions;
import com.javaclaw.desktop.DesktopTestFixtures;

/** MCP Presenter 测试使用的同步内存边界。 */
final class TestMcpSettingsGateway implements McpSettingsGateway {
    private final Workspace workspace = DesktopTestFixtures.workspace();
    private final Map<String, List<McpEndpoint>> endpoints = new LinkedHashMap<>();
    private final Map<CredentialRef, CredentialMetadata> credentials = new LinkedHashMap<>();
    private final Deque<CompletionStage<McpCatalogPage>> catalogResponses = new ArrayDeque<>();
    private final Deque<CompletionStage<McpResourcePage>> resourceResponses = new ArrayDeque<>();
    private final Deque<CompletionStage<McpResourceReadResult>> resourceReadResponses = new ArrayDeque<>();
    private final Deque<CompletionStage<McpPromptPage>> promptResponses = new ArrayDeque<>();
    private final Deque<CompletionStage<McpPromptResult>> promptResultResponses = new ArrayDeque<>();
    private int nextCredential = 1;
    private boolean failEndpointWrite;
    private boolean noWorkspaces;
    private Optional<McpOAuthAuthorization> oauth = Optional.empty();

    @Override
    public CompletionStage<List<Workspace>> workspaces() {
        return completed(noWorkspaces ? List.of() : List.of(workspace));
    }

    @Override
    public CompletionStage<List<PrivateNetworkGrant>> privateNetworkGrants(WorkspaceId workspaceId) {
        return completed(List.of());
    }

    @Override
    public CompletionStage<List<McpEndpoint>> mcpEndpoints(WorkspaceId workspaceId) {
        return completed(endpoints.values().stream().map(List::getLast).toList());
    }

    @Override
    public CompletionStage<McpEndpoint> mcpEndpoint(String endpointId) {
        return completed(requireEndpoint(endpointId));
    }

    @Override
    public CompletionStage<List<McpEndpoint>> mcpEndpointHistory(String endpointId) {
        return completed(List.copyOf(endpoints.getOrDefault(endpointId, List.of())));
    }

    @Override
    public CompletionStage<McpEndpoint> createMcpEndpoint(
            String endpointId, McpEndpointSpec spec, CommandOptions options) {
        if (failEndpointWrite) {
            return failed(new IllegalStateException("模拟 Endpoint 写入失败"));
        }
        requireRevision(options, 0);
        McpEndpoint created = endpoint(endpointId, 1, McpEndpointState.DISABLED, 0, spec);
        endpoints.put(endpointId, new ArrayList<>(List.of(created)));
        return completed(created);
    }

    @Override
    public CompletionStage<McpEndpoint> updateMcpEndpoint(
            String endpointId, McpEndpointSpec spec, CommandOptions options) {
        McpEndpoint current = requireEndpoint(endpointId);
        requireRevision(options, current.revision());
        McpEndpoint updated =
                endpoint(endpointId, current.revision() + 1, current.state(), current.catalogRevision(), spec);
        endpoints.get(endpointId).add(updated);
        return completed(updated);
    }

    @Override
    public CompletionStage<McpEndpoint> setMcpEndpointEnabled(
            McpEndpoint endpoint, boolean enabled, CommandOptions options) {
        requireRevision(options, endpoint.revision());
        McpEndpoint updated = endpoint(
                endpoint.id(),
                endpoint.revision() + 1,
                enabled ? McpEndpointState.ENABLED : McpEndpointState.DISABLED,
                endpoint.catalogRevision(),
                endpoint.spec());
        endpoints.get(endpoint.id()).add(updated);
        return completed(updated);
    }

    @Override
    public CompletionStage<McpHealth> probeMcpEndpoint(String endpointId) {
        McpEndpoint endpoint = requireEndpoint(endpointId);
        return completed(new McpHealth(
                endpointId,
                endpoint.revision(),
                McpHealthState.HEALTHY,
                Optional.of("2026-07-28"),
                Optional.empty(),
                Instant.now()));
    }

    @Override
    public CompletionStage<McpHealth> mcpHealth(String endpointId) {
        return probeMcpEndpoint(endpointId);
    }

    @Override
    public CompletionStage<McpEndpoint> refreshMcpCatalog(McpEndpoint endpoint, CommandOptions options) {
        requireRevision(options, endpoint.revision());
        McpEndpoint updated = endpoint(
                endpoint.id(),
                endpoint.revision() + 1,
                endpoint.state(),
                endpoint.catalogRevision() + 1,
                endpoint.spec());
        endpoints.get(endpoint.id()).add(updated);
        return completed(updated);
    }

    @Override
    public CompletionStage<McpCatalogPage> mcpCatalog(
            String endpointId, Optional<McpCatalogKind> kind, Optional<String> cursor, int limit) {
        if (!catalogResponses.isEmpty()) {
            return catalogResponses.removeFirst();
        }
        McpCatalogEntry tool = McpCatalogEntry.tool(
                "search",
                Optional.of("Search"),
                "检索远端资料",
                new CanonicalPayload("{\"type\":\"object\"}"),
                new CanonicalPayload("{\"type\":\"object\"}"));
        return completed(new McpCatalogPage(List.of(tool), Optional.empty()));
    }

    @Override
    public CompletionStage<McpResourcePage> mcpResources(String endpointId, Optional<String> cursor) {
        if (!resourceResponses.isEmpty()) {
            return resourceResponses.removeFirst();
        }
        return completed(new McpResourcePage(
                List.of(new McpResourceDescriptor(
                        "guide",
                        "docs://guide",
                        Optional.of("Guide"),
                        Optional.empty(),
                        Optional.of("text/plain"),
                        Optional.of(5L))),
                Optional.empty(),
                McpCacheScope.PRIVATE,
                0,
                List.of()));
    }

    @Override
    public CompletionStage<McpResourceReadResult> readMcpResource(String endpointId, String uri) {
        if (!resourceReadResponses.isEmpty()) {
            return resourceReadResponses.removeFirst();
        }
        return completed(new McpResourceReadResult(
                List.of(new McpResourceContent(uri, Optional.of("text/plain"), Optional.of("hello"), Optional.empty())),
                McpCacheScope.PRIVATE,
                0,
                List.of()));
    }

    @Override
    public CompletionStage<McpPromptPage> mcpPrompts(String endpointId, Optional<String> cursor) {
        if (!promptResponses.isEmpty()) {
            return promptResponses.removeFirst();
        }
        return completed(new McpPromptPage(
                List.of(new McpPromptDescriptor("review", Optional.empty(), Optional.empty(), List.of())),
                Optional.empty(),
                McpCacheScope.PRIVATE,
                0,
                List.of()));
    }

    @Override
    public CompletionStage<McpPromptResult> getMcpPrompt(
            String endpointId, String name, Map<String, String> arguments) {
        if (!promptResultResponses.isEmpty()) {
            return promptResultResponses.removeFirst();
        }
        return completed(new McpPromptResult(
                Optional.empty(),
                List.of(new McpPromptMessage(
                        McpSamplingRole.USER, new CanonicalPayload("{\"type\":\"text\",\"text\":\"review\"}"))),
                List.of()));
    }

    @Override
    public CompletionStage<McpOAuthAuthorization> startMcpOAuth(McpEndpoint endpoint, CommandOptions options) {
        McpOAuthAuthorization authorization = new McpOAuthAuthorization(
                "oauth-test",
                endpoint.id(),
                endpoint.revision(),
                "login.example.test",
                McpOAuthState.PENDING,
                Instant.now().plusSeconds(600),
                Optional.empty(),
                Instant.now());
        oauth = Optional.of(authorization);
        return completed(authorization);
    }

    @Override
    public CompletionStage<Optional<McpOAuthAuthorization>> latestMcpOAuth(String endpointId) {
        return completed(oauth.filter(value -> value.endpointId().equals(endpointId)));
    }

    @Override
    public CompletionStage<McpOAuthAuthorization> cancelMcpOAuth(
            McpOAuthAuthorization authorization, CommandOptions options) {
        McpOAuthAuthorization cancelled = new McpOAuthAuthorization(
                authorization.id(),
                authorization.endpointId(),
                authorization.endpointRevision(),
                authorization.authorizationHost(),
                McpOAuthState.CANCELLED,
                authorization.expiresAt(),
                Optional.empty(),
                Instant.now());
        oauth = Optional.of(cancelled);
        return completed(cancelled);
    }

    @Override
    public CompletionStage<Optional<CredentialMetadata>> credential(CredentialRef reference) {
        return completed(Optional.ofNullable(credentials.get(reference)));
    }

    @Override
    public CompletionStage<CredentialMetadata> createCredential(
            String namespace, char[] secret, CommandOptions options) {
        requireRevision(options, 0);
        CredentialRef reference = new CredentialRef(namespace, "credential-" + nextCredential++);
        CredentialMetadata metadata = new CredentialMetadata(reference, 1, Instant.now());
        credentials.put(reference, metadata);
        return completed(metadata);
    }

    @Override
    public CompletionStage<CredentialMetadata> rotateCredential(
            CredentialRef reference, char[] secret, CommandOptions options) {
        CredentialMetadata current = credentials.get(reference);
        requireRevision(options, current.revision());
        CredentialMetadata updated = new CredentialMetadata(reference, current.revision() + 1, Instant.now());
        credentials.put(reference, updated);
        return completed(updated);
    }

    @Override
    public CompletionStage<CredentialClearReceipt> clearCredential(CredentialRef reference, CommandOptions options) {
        CredentialMetadata current = credentials.remove(reference);
        requireRevision(options, current.revision());
        return completed(new CredentialClearReceipt(reference, current.revision() + 1, Instant.now()));
    }

    void failEndpointWrite() {
        failEndpointWrite = true;
    }

    void withoutWorkspaces() {
        noWorkspaces = true;
    }

    void enqueueCatalog(CompletionStage<McpCatalogPage> response) {
        catalogResponses.addLast(response);
    }

    void enqueueResources(CompletionStage<McpResourcePage> response) {
        resourceResponses.addLast(response);
    }

    void enqueueResourceRead(CompletionStage<McpResourceReadResult> response) {
        resourceReadResponses.addLast(response);
    }

    void enqueuePrompts(CompletionStage<McpPromptPage> response) {
        promptResponses.addLast(response);
    }

    void enqueuePromptResult(CompletionStage<McpPromptResult> response) {
        promptResultResponses.addLast(response);
    }

    McpEndpoint addSignedBundleEndpoint() {
        McpEndpointSpec spec = new McpEndpointSpec(
                workspace.id(),
                "Bundle MCP",
                McpTransport.SIGNED_BUNDLE_STDIO,
                Optional.empty(),
                Optional.of("trusted.bundle"),
                com.javaclaw.api.McpAuthType.NONE,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Duration.ofSeconds(30));
        McpEndpoint created = endpoint("bundle-mcp", 1, McpEndpointState.ENABLED, 1, spec);
        endpoints.put(created.id(), new ArrayList<>(List.of(created)));
        return created;
    }

    int credentialCount() {
        return credentials.size();
    }

    void authorizeOAuth() {
        McpOAuthAuthorization pending = oauth.orElseThrow();
        McpEndpoint current = requireEndpoint(pending.endpointId());
        CredentialRef reference = new CredentialRef("oauth", "sealed-token");
        McpEndpointSpec spec = new McpEndpointSpec(
                current.spec().workspaceId(),
                current.spec().displayName(),
                current.spec().transport(),
                current.spec().endpointUri(),
                current.spec().signedBundleId(),
                current.spec().authType(),
                Optional.of(reference),
                current.spec().apiKeyHeader(),
                current.spec().privateNetworkGrant(),
                current.spec().requestTimeout());
        McpEndpoint updated =
                endpoint(current.id(), current.revision() + 1, current.state(), current.catalogRevision(), spec);
        endpoints.get(current.id()).add(updated);
        oauth = Optional.of(new McpOAuthAuthorization(
                pending.id(),
                pending.endpointId(),
                pending.endpointRevision(),
                pending.authorizationHost(),
                McpOAuthState.AUTHORIZED,
                pending.expiresAt(),
                Optional.empty(),
                Instant.now()));
    }

    private McpEndpoint requireEndpoint(String id) {
        return endpoints.get(id).getLast();
    }

    private static McpEndpoint endpoint(
            String id, long revision, McpEndpointState state, long catalogRevision, McpEndpointSpec spec) {
        Instant now = Instant.now();
        return new McpEndpoint(id, revision, state, catalogRevision, spec, now, now);
    }

    private static void requireRevision(CommandOptions options, long expected) {
        if (options.expectedRevision() != expected) {
            throw new IllegalStateException("unexpected revision");
        }
    }

    private static <T> CompletionStage<T> completed(T value) {
        return CompletableFuture.completedFuture(value);
    }

    private static <T> CompletionStage<T> failed(Throwable failure) {
        return CompletableFuture.failedFuture(failure);
    }
}
