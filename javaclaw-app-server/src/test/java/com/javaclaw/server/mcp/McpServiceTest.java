package com.javaclaw.server.mcp;

import java.net.URI;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.javaclaw.api.CancellationSource;
import com.javaclaw.api.CancellationToken;
import com.javaclaw.api.CanonicalPayload;
import com.javaclaw.api.CredentialRef;
import com.javaclaw.api.McpAuthType;
import com.javaclaw.api.McpCacheScope;
import com.javaclaw.api.McpCatalogEntry;
import com.javaclaw.api.McpCatalogKind;
import com.javaclaw.api.McpCatalogPage;
import com.javaclaw.api.McpCatalogRefreshState;
import com.javaclaw.api.McpEndpoint;
import com.javaclaw.api.McpEndpointSpec;
import com.javaclaw.api.McpEndpointState;
import com.javaclaw.api.McpHealthState;
import com.javaclaw.api.McpInvocationRequest;
import com.javaclaw.api.McpInvocationResult;
import com.javaclaw.api.McpPromptDescriptor;
import com.javaclaw.api.McpPromptMessage;
import com.javaclaw.api.McpPromptPage;
import com.javaclaw.api.McpPromptResult;
import com.javaclaw.api.McpProtocol;
import com.javaclaw.api.McpRemoteSession;
import com.javaclaw.api.McpResourceContent;
import com.javaclaw.api.McpResourceDescriptor;
import com.javaclaw.api.McpResourcePage;
import com.javaclaw.api.McpResourceReadResult;
import com.javaclaw.api.McpSamplingRole;
import com.javaclaw.api.McpTransport;
import com.javaclaw.api.ToolDescriptor;
import com.javaclaw.api.Workspace;
import com.javaclaw.api.WorkspaceId;
import com.javaclaw.extension.spi.McpClientInteractionPort;
import com.javaclaw.extension.spi.McpCredentialStatusPort;
import com.javaclaw.extension.spi.McpNetworkAuthorizationPort;
import com.javaclaw.extension.spi.McpRemotePort;
import com.javaclaw.protocol.CanonicalJson;
import com.javaclaw.protocol.CoreRpcContracts;
import com.javaclaw.server.persistence.CommandIdentity;
import com.javaclaw.server.persistence.CoreCommandService;
import com.javaclaw.server.persistence.H2Database;
import com.javaclaw.server.persistence.PersistenceException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class McpServiceTest {
    private static final Instant NOW = Instant.parse("2026-09-01T00:00:00Z");

    @TempDir
    Path temporaryDirectory;

    private CanonicalJson json;
    private H2Database database;
    private Workspace workspace;
    private FakeRemote remote;
    private McpService service;

    @BeforeEach
    void 初始化全新McpHost() {
        database = new H2Database(temporaryDirectory.resolve("data-v6"));
        database.initialize();
        json = new CanonicalJson();
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        CoreCommandService core = new CoreCommandService(database, json, clock);
        Path root = temporaryDirectory.resolve("workspace");
        CoreRpcContracts.WorkspaceCreatePayload payload = new CoreRpcContracts.WorkspaceCreatePayload("MCP", root);
        workspace = core.createWorkspace(identity("workspace/create", "workspace", 0, payload), "MCP", root);
        remote = new FakeRemote(json);
        service = service(remote, ignored -> {}, ignored -> true);
    }

    @Test
    void Https端点完成固定协议分页目录冻结调用与实时停用() throws Exception {
        McpEndpoint created = service.createHttps(
                identity("mcp/endpoint/create", "create", 0, writePayload("docs", httpsSpec())), "docs", httpsSpec());
        assertEquals(McpEndpointState.DISABLED, created.state());
        McpEndpoint enabled = service.setState(
                identity("mcp/endpoint/enable", "enable", 1, endpointPayload("docs")),
                "docs",
                McpEndpointState.ENABLED);
        assertEquals(McpHealthState.HEALTHY, service.probe("docs").state());

        remote.pages.add(new McpCatalogPage(List.of(tool("search")), Optional.of("next")));
        remote.pages.add(new McpCatalogPage(List.of(external(McpCatalogKind.RESOURCE, "manual")), Optional.empty()));
        McpEndpoint refreshed = service.refreshCatalog(
                identity("mcp/catalog/refresh", "refresh", enabled.revision(), endpointPayload("docs")), "docs");

        assertEquals(1, refreshed.catalogRevision());
        var refresh = service.catalogRefresh("docs").orElseThrow();
        assertEquals(McpCatalogRefreshState.COMPLETED, refresh.state());
        assertEquals(2, refresh.pagesCompleted());
        assertEquals(2, refresh.entriesDiscovered());
        assertEquals(1L, refresh.committedCatalogRevision().orElseThrow());
        assertEquals(
                2, service.catalog("docs", Optional.empty(), 0, 10).entries().size());
        ToolDescriptor frozen = service.toolDescriptors(workspace.id()).getFirst();
        McpInvocationResult result = service.invoke(
                com.javaclaw.api.TurnId.random(),
                workspace.id(),
                frozen,
                json.parse("{\"query\":\"v5\"}"),
                "call-1",
                new CancellationSource());
        assertTrue(result.successful());
        assertEquals(1, remote.invocations);
        McpInvocationResult recovered = service.invoke(
                com.javaclaw.api.TurnId.random(),
                workspace.id(),
                frozen,
                json.parse("{\"query\":\"v5\"}"),
                "call-1",
                new CancellationSource());
        assertEquals(result, recovered);
        assertEquals(1, remote.invocations);

        service.setState(
                identity("mcp/endpoint/disable", "disable", refreshed.revision(), endpointPayload("docs")),
                "docs",
                McpEndpointState.DISABLED);
        assertThrows(
                PersistenceException.class,
                () -> service.invoke(
                        com.javaclaw.api.TurnId.random(),
                        workspace.id(),
                        frozen,
                        json.parse("{\"query\":\"v5\"}"),
                        "call-2",
                        new CancellationSource()));
    }

    @Test
    void 协议不匹配时健康明确失败且目录不降级提交() {
        McpEndpoint endpoint = enabledEndpoint("protocol");
        remote.protocol = "2025-11-25";

        assertEquals(
                McpHealthState.PROTOCOL_MISMATCH, service.probe(endpoint.id()).state());
        assertThrows(
                PersistenceException.class,
                () -> service.refreshCatalog(
                        identity(
                                "mcp/catalog/refresh",
                                "bad-protocol",
                                endpoint.revision(),
                                endpointPayload(endpoint.id())),
                        endpoint.id()));
        assertEquals(0, service.requireLatest(endpoint.id()).catalogRevision());
        assertEquals(
                McpCatalogRefreshState.FAILED,
                service.catalogRefresh(endpoint.id()).orElseThrow().state());
    }

    @Test
    void Https外部Resource与Prompt只经显式读取返回() {
        McpEndpoint endpoint = enabledEndpoint("external");

        McpResourcePage resources = service.resources(endpoint.id(), Optional.empty());
        McpResourceReadResult resource = service.readResource(endpoint.id(), "docs://guide");
        McpPromptPage prompts = service.prompts(endpoint.id(), Optional.empty());
        McpPromptResult prompt = service.getPrompt(endpoint.id(), "review", Map.of("topic", "v5"));

        assertEquals("docs://guide", resources.resources().getFirst().uri());
        assertEquals("hello", resource.contents().getFirst().text().orElseThrow());
        assertEquals("review", prompts.prompts().getFirst().name());
        assertEquals(McpSamplingRole.USER, prompt.messages().getFirst().role());
        McpEndpoint disabled = service.setState(
                identity(
                        "mcp/endpoint/disable",
                        "external-disable",
                        endpoint.revision(),
                        endpointPayload(endpoint.id())),
                endpoint.id(),
                McpEndpointState.DISABLED);
        assertThrows(PersistenceException.class, () -> service.resources(disabled.id(), Optional.empty()));
    }

    @Test
    void OAuth凭据只绑定到授权启动时冻结的Endpoint版本() {
        McpEndpointSpec oauth = new McpEndpointSpec(
                workspace.id(),
                "OAuth MCP",
                McpTransport.STREAMABLE_HTTPS,
                Optional.of(URI.create("https://oauth-mcp.example/rpc")),
                Optional.empty(),
                McpAuthType.OAUTH_2_1_PKCE,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Duration.ofSeconds(30));
        McpEndpoint created = service.createHttps(
                identity("mcp/endpoint/create", "oauth-create", 0, writePayload("oauth", oauth)), "oauth", oauth);
        CredentialRef reference = new CredentialRef("oauth", "oauth-credential");
        CommandIdentity attach =
                identity("mcp/endpoint/oauth-attach", "oauth-attach", created.revision(), endpointPayload("oauth"));

        McpEndpoint attached = service.attachOAuthCredential(attach, "oauth", reference);

        assertEquals(reference, attached.spec().credential().orElseThrow());
        assertEquals(attached, service.attachOAuthCredential(attach, "oauth", reference));
        CommandIdentity stale =
                identity("mcp/endpoint/oauth-attach", "oauth-stale", created.revision(), endpointPayload("oauth"));
        assertThrows(PersistenceException.class, () -> service.attachOAuthCredential(stale, "oauth", reference));
    }

    @Test
    void 重复Catalog身份拒绝并保持旧目录原子不变() {
        McpEndpoint endpoint = enabledEndpoint("duplicate");
        remote.pages.add(new McpCatalogPage(List.of(tool("same")), Optional.of("next")));
        remote.pages.add(new McpCatalogPage(List.of(tool("same")), Optional.empty()));

        assertThrows(
                PersistenceException.class,
                () -> service.refreshCatalog(
                        identity(
                                "mcp/catalog/refresh",
                                "duplicate",
                                endpoint.revision(),
                                endpointPayload(endpoint.id())),
                        endpoint.id()));
        assertEquals(0, service.requireLatest(endpoint.id()).catalogRevision());
    }

    @Test
    void 用户入口拒绝BundleStdio而签名平台入口允许() {
        McpEndpointSpec stdio = new McpEndpointSpec(
                workspace.id(),
                "Bundle",
                McpTransport.SIGNED_BUNDLE_STDIO,
                Optional.empty(),
                Optional.of("signed.bundle"),
                McpAuthType.NONE,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Duration.ofSeconds(10));
        assertThrows(
                PersistenceException.class,
                () -> service.createHttps(
                        identity("mcp/endpoint/create", "stdio-user", 0, writePayload("stdio", stdio)),
                        "stdio",
                        stdio));
        com.javaclaw.protocol.McpRpcContracts.SignedBundleRegisterPayload request =
                new com.javaclaw.protocol.McpRpcContracts.SignedBundleRegisterPayload(
                        "stdio", workspace.id(), "signed.bundle", "Bundle", Duration.ofSeconds(10));
        McpEndpoint registered =
                service.registerSignedBundle(identity("mcp/stdio/register", "stdio-bundle", 0, request), request);
        assertEquals(McpTransport.SIGNED_BUNDLE_STDIO, registered.spec().transport());
    }

    @Test
    void 远端调用失败后标记未知结果并禁止自动重试() {
        McpEndpoint endpoint = enabledEndpoint("unknown");
        remote.pages.add(new McpCatalogPage(List.of(tool("write")), Optional.empty()));
        McpEndpoint refreshed = service.refreshCatalog(
                identity("mcp/catalog/refresh", "unknown-refresh", endpoint.revision(), endpointPayload(endpoint.id())),
                endpoint.id());
        ToolDescriptor frozen = service.toolDescriptors(workspace.id()).getFirst();
        remote.failInvocation = true;

        assertThrows(
                IllegalStateException.class,
                () -> service.invoke(
                        com.javaclaw.api.TurnId.random(),
                        workspace.id(),
                        frozen,
                        json.parse("{\"value\":\"once\"}"),
                        "unknown-effect",
                        new CancellationSource()));
        remote.failInvocation = false;
        PersistenceException blocked = assertThrows(
                PersistenceException.class,
                () -> service.invoke(
                        com.javaclaw.api.TurnId.random(),
                        workspace.id(),
                        frozen,
                        json.parse("{\"value\":\"once\"}"),
                        "unknown-effect",
                        new CancellationSource()));

        assertTrue(blocked.getMessage().contains("UNKNOWN_OUTCOME"));
        assertEquals(1, remote.invocations);
        assertEquals(
                refreshed.catalogRevision(),
                service.requireLatest(endpoint.id()).catalogRevision());
    }

    @Test
    void 更新历史重放与非法身份边界全部拒绝() {
        McpEndpointSpec original = httpsSpec();
        CommandIdentity create = identity("mcp/endpoint/create", "replay-create", 0, writePayload("replay", original));
        McpEndpoint created = service.createHttps(create, "replay", original);
        assertEquals(created, service.createHttps(create, "replay", original));

        McpEndpointSpec renamed = httpsSpec(workspace.id(), "Docs v2", McpAuthType.NONE, Optional.empty());
        McpEndpoint updated = service.updateHttps(
                identity("mcp/endpoint/update", "update-replay", 1, writePayload("replay", renamed)),
                "replay",
                renamed);
        assertEquals(List.of(created, updated), service.history("replay"));

        McpEndpointSpec conflicting = httpsSpec(workspace.id(), "Conflict", McpAuthType.NONE, Optional.empty());
        CommandIdentity reusedKey =
                identity("mcp/endpoint/create", "replay-create", 0, writePayload("replay", conflicting));
        assertThrows(PersistenceException.class, () -> service.createHttps(reusedKey, "replay", conflicting));
        assertThrows(
                PersistenceException.class,
                () -> service.createHttps(
                        identity("mcp/endpoint/create", "future", 1, writePayload("future", original)),
                        "future",
                        original));

        McpEndpointSpec foreign = httpsSpec(WorkspaceId.random(), "Foreign", McpAuthType.NONE, Optional.empty());
        assertThrows(
                PersistenceException.class,
                () -> service.updateHttps(
                        identity("mcp/endpoint/update", "foreign", 2, writePayload("replay", foreign)),
                        "replay",
                        foreign));
        PersistenceException invalidIdentifier =
                assertThrows(PersistenceException.class, () -> service.requireLatest("bad id"));
        assertEquals(PersistenceException.Kind.INTERNAL, invalidIdentifier.kind());
        assertTrue(invalidIdentifier.getCause() instanceof IllegalArgumentException);
        assertThrows(
                PersistenceException.class,
                () -> service.attachOAuthCredential(
                        identity("mcp/endpoint/oauth-attach", "attach-none", 2, endpointPayload("replay")),
                        "replay",
                        new CredentialRef("oauth", "unexpected")));
    }

    @Test
    void 停用缺失凭据与远端异常健康均保存脱敏投影() {
        McpEndpointSpec disabledSpec = httpsSpec();
        service.createHttps(
                identity("mcp/endpoint/create", "disabled-create", 0, writePayload("disabled", disabledSpec)),
                "disabled",
                disabledSpec);
        assertEquals(McpHealthState.UNAVAILABLE, service.probe("disabled").state());
        assertEquals(McpHealthState.UNAVAILABLE, service.health("disabled").state());
        service.setState(
                identity("mcp/endpoint/enable", "enable-disabled", 1, endpointPayload("disabled")),
                "disabled",
                McpEndpointState.ENABLED);
        assertEquals(McpHealthState.UNKNOWN, service.health("disabled").state());

        McpService locked = service(remote, ignored -> {}, ignored -> false);
        McpEndpointSpec bearer = httpsSpec(
                workspace.id(), "Bearer", McpAuthType.BEARER, Optional.of(new CredentialRef("mcp", "missing")));
        enabledEndpoint(locked, "locked", bearer);
        assertEquals(McpHealthState.AUTH_REQUIRED, locked.probe("locked").state());
        McpEndpointSpec oauth = httpsSpec(workspace.id(), "OAuth", McpAuthType.OAUTH_2_1_PKCE, Optional.empty());
        enabledEndpoint(locked, "oauth-missing", oauth);
        assertEquals(McpHealthState.AUTH_REQUIRED, locked.probe("oauth-missing").state());

        remote.failInitialize = true;
        McpEndpoint failed = enabledEndpoint("remote-failure");
        assertEquals(McpHealthState.UNAVAILABLE, service.probe(failed.id()).state());
        remote.failInitialize = false;

        assertThrows(PersistenceException.class, () -> service.requireLatest("missing"));
    }

    @Test
    void 网络授权失败被脱敏而Bundle探测跳过网络授权() {
        McpService blocked = service(
                remote,
                endpoint -> {
                    throw new SecurityException("DNS policy rejected");
                },
                ignored -> true);
        McpEndpoint blockedEndpoint = enabledEndpoint(blocked, "blocked-network", httpsSpec());
        assertEquals(
                McpHealthState.UNAVAILABLE, blocked.probe(blockedEndpoint.id()).state());

        McpEndpointSpec bearer = httpsSpec(
                workspace.id(), "Bearer", McpAuthType.BEARER, Optional.of(new CredentialRef("mcp", "available")));
        McpEndpoint authenticated = enabledEndpoint(service, "available-credential", bearer);
        assertEquals(McpHealthState.HEALTHY, service.probe(authenticated.id()).state());

        com.javaclaw.protocol.McpRpcContracts.SignedBundleRegisterPayload request =
                new com.javaclaw.protocol.McpRpcContracts.SignedBundleRegisterPayload(
                        "bundle-health", workspace.id(), "signed.bundle", "Bundle", Duration.ofSeconds(10));
        McpEndpoint registered =
                service.registerSignedBundle(identity("mcp/stdio/register", "bundle-health", 0, request), request);
        service.setState(
                identity("mcp/endpoint/enable", "enable-bundle-health", 1, endpointPayload(registered.id())),
                registered.id(),
                McpEndpointState.ENABLED);
        assertEquals(McpHealthState.HEALTHY, service.probe(registered.id()).state());
    }

    private McpEndpoint enabledEndpoint(String id) {
        McpEndpointSpec spec = httpsSpec();
        return enabledEndpoint(service, id, spec);
    }

    private McpEndpoint enabledEndpoint(McpService target, String id, McpEndpointSpec spec) {
        target.createHttps(identity("mcp/endpoint/create", id + "-create", 0, writePayload(id, spec)), id, spec);
        return target.setState(
                identity("mcp/endpoint/enable", id + "-enable", 1, endpointPayload(id)), id, McpEndpointState.ENABLED);
    }

    private McpEndpointSpec httpsSpec() {
        return httpsSpec(workspace.id(), "Docs MCP", McpAuthType.NONE, Optional.empty());
    }

    private McpEndpointSpec httpsSpec(
            WorkspaceId workspaceId, String name, McpAuthType authType, Optional<CredentialRef> credential) {
        return new McpEndpointSpec(
                workspaceId,
                name,
                McpTransport.STREAMABLE_HTTPS,
                Optional.of(URI.create("https://mcp.example/rpc")),
                Optional.empty(),
                authType,
                credential,
                Optional.empty(),
                Optional.empty(),
                Duration.ofSeconds(30));
    }

    private McpCatalogEntry tool(String name) {
        return McpCatalogEntry.tool(
                name,
                Optional.of("Search"),
                "检索远端资料",
                json.parse("{\"type\":\"object\"}"),
                json.parse("{\"type\":\"object\"}"));
    }

    private McpCatalogEntry external(McpCatalogKind kind, String name) {
        return new McpCatalogEntry(
                kind, name, Optional.empty(), "外部数据", Optional.empty(), Optional.empty(), Optional.empty());
    }

    private Object writePayload(String id, McpEndpointSpec spec) {
        return new com.javaclaw.protocol.McpRpcContracts.EndpointWritePayload(id, spec);
    }

    private Object endpointPayload(String id) {
        return new com.javaclaw.protocol.McpRpcContracts.EndpointQuery(id);
    }

    private CommandIdentity identity(String method, String key, long revision, Object payload) {
        CanonicalPayload request = json.encode(new DigestInput(revision, payload));
        return new CommandIdentity(method, key, revision, request.sha256());
    }

    private McpService service(
            McpRemotePort remotePort, McpNetworkAuthorizationPort network, McpCredentialStatusPort credentials) {
        return new McpService(
                database,
                new McpServiceDependencies(
                        remotePort,
                        (turnId, workspaceId, endpoint) -> rejectingInteractions(),
                        network,
                        credentials,
                        bundleId -> new SignedBundleMcpLaunch(
                                bundleId,
                                1,
                                "mcp",
                                json.parse("{\"protocolVersion\":\"2026-07-28\"}"),
                                temporaryDirectory,
                                List.of("/usr/bin/true"),
                                Duration.ofSeconds(10),
                                new com.javaclaw.api.ResourceLimits(1024, 1024, 1, 8))),
                json,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static McpClientInteractionPort rejectingInteractions() {
        return new McpClientInteractionPort() {
            @Override
            public Optional<CanonicalPayload> elicit(
                    com.javaclaw.api.McpElicitationRequest request, CancellationToken cancellation) {
                return Optional.empty();
            }

            @Override
            public Optional<CanonicalPayload> sample(
                    com.javaclaw.api.McpSamplingRequest request, CancellationToken cancellation) {
                return Optional.empty();
            }
        };
    }

    private record DigestInput(long expectedRevision, Object payload) {}

    private static final class FakeRemote implements McpRemotePort {
        private final CanonicalJson json;
        private final Queue<McpCatalogPage> pages = new ArrayDeque<>();
        private String protocol = McpProtocol.VERSION;
        private int invocations;
        private boolean failInitialize;
        private boolean failInvocation;

        private FakeRemote(CanonicalJson json) {
            this.json = json;
        }

        @Override
        public McpRemoteSession initialize(
                McpEndpoint endpoint, String requiredProtocol, CancellationToken cancellation) {
            if (failInitialize) {
                throw new IllegalStateException("remote initialize failed");
            }
            return new McpRemoteSession(protocol, Set.of("tools", "resources"));
        }

        @Override
        public McpCatalogPage catalog(McpEndpoint endpoint, Optional<String> cursor, CancellationToken cancellation) {
            return pages.remove();
        }

        @Override
        public McpResourcePage resources(
                McpEndpoint endpoint, Optional<String> cursor, CancellationToken cancellation) {
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

        @Override
        public McpResourceReadResult readResource(McpEndpoint endpoint, String uri, CancellationToken cancellation) {
            return new McpResourceReadResult(
                    List.of(new McpResourceContent(
                            uri, Optional.of("text/plain"), Optional.of("hello"), Optional.empty())),
                    McpCacheScope.PRIVATE,
                    0,
                    List.of());
        }

        @Override
        public McpPromptPage prompts(McpEndpoint endpoint, Optional<String> cursor, CancellationToken cancellation) {
            return new McpPromptPage(
                    List.of(new McpPromptDescriptor("review", Optional.empty(), Optional.empty(), List.of())),
                    Optional.empty(),
                    McpCacheScope.PRIVATE,
                    0,
                    List.of());
        }

        @Override
        public McpPromptResult getPrompt(
                McpEndpoint endpoint, String name, Map<String, String> arguments, CancellationToken cancellation) {
            return new McpPromptResult(
                    Optional.empty(),
                    List.of(new McpPromptMessage(McpSamplingRole.USER, json.parse("{\"type\":\"text\"}"))),
                    List.of());
        }

        @Override
        public McpInvocationResult invoke(
                McpEndpoint endpoint,
                McpInvocationRequest request,
                McpClientInteractionPort interactions,
                CancellationToken cancellation) {
            invocations++;
            if (failInvocation) {
                throw new IllegalStateException("remote connection closed after dispatch");
            }
            return new McpInvocationResult(
                    true, json.parse("{\"content\":\"ok\"}"), Optional.of("remote-1"), List.of());
        }
    }
}
