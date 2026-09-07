package com.javaclaw.server.rpc;

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

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.javaclaw.api.CancellationToken;
import com.javaclaw.api.CanonicalPayload;
import com.javaclaw.api.McpAuthType;
import com.javaclaw.api.McpCacheScope;
import com.javaclaw.api.McpCatalogEntry;
import com.javaclaw.api.McpCatalogPage;
import com.javaclaw.api.McpEndpoint;
import com.javaclaw.api.McpEndpointSpec;
import com.javaclaw.api.McpEndpointState;
import com.javaclaw.api.McpHealth;
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
import com.javaclaw.api.TurnId;
import com.javaclaw.api.Workspace;
import com.javaclaw.builtin.contracts.BuiltinExtensionIds;
import com.javaclaw.extension.spi.McpClientInteractionPort;
import com.javaclaw.extension.spi.McpOAuthAuthorizationRequest;
import com.javaclaw.extension.spi.McpOAuthBrokerPort;
import com.javaclaw.extension.spi.McpOAuthExchange;
import com.javaclaw.extension.spi.McpRemotePort;
import com.javaclaw.protocol.BuiltinExtensionRpcContracts;
import com.javaclaw.protocol.CapabilityAdvertisement;
import com.javaclaw.protocol.ClientInfo;
import com.javaclaw.protocol.CoreRpcContracts;
import com.javaclaw.protocol.InitializeParams;
import com.javaclaw.protocol.JsonRpcRequest;
import com.javaclaw.protocol.JsonRpcResponse;
import com.javaclaw.protocol.McpRpcContracts;
import com.javaclaw.protocol.ProtocolErrorCode;
import com.javaclaw.protocol.ProtocolVersion;
import com.javaclaw.protocol.RpcId;
import com.javaclaw.protocol.StableCapabilities;
import com.javaclaw.protocol.WriteCommand;
import com.javaclaw.runtime.ModelCapabilities;
import com.javaclaw.runtime.ModelEventSink;
import com.javaclaw.runtime.ModelFinishReason;
import com.javaclaw.runtime.ModelGateway;
import com.javaclaw.runtime.ModelInvocation;
import com.javaclaw.runtime.ModelInvocationResult;
import com.javaclaw.runtime.ModelUsage;
import com.javaclaw.server.AppServerBootstrap;
import com.javaclaw.server.extension.BuiltinIsolatedServices;
import com.javaclaw.server.mcp.McpRuntimePorts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class McpRpcHandlersTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-01T00:00:00Z"), ZoneOffset.UTC);

    @TempDir
    Path temporaryDirectory;

    private AppServerBootstrap.Components components;
    private AppServerSession session;
    private FakeRemote remote;
    private Workspace workspace;

    @BeforeEach
    void 启动带内存Mcp端口的完整会话() {
        remote = new FakeRemote();
        components = AppServerBootstrap.create(
                temporaryDirectory.resolve("data-v6"),
                CLOCK,
                new NoOpModel(),
                required -> {},
                BuiltinIsolatedServices.browserUnavailable(),
                runtimePorts(remote));
        session = components.newSession();
        decode(invoke("initialize/session", initialization()), com.javaclaw.protocol.InitializeResult.class);
        workspace = write(
                "workspace/create",
                "workspace",
                0,
                new CoreRpcContracts.WorkspaceCreatePayload("MCP RPC", temporaryDirectory.resolve("workspace")),
                Workspace.class);
    }

    @AfterEach
    void 关闭完整会话() throws Exception {
        session.close();
        components.close();
    }

    @Test
    void 端点管理健康与目录路由返回权威投影() {
        McpEndpoint created = write(
                "mcp/endpoint/create",
                "create-docs",
                0,
                new McpRpcContracts.EndpointWritePayload("docs", httpsSpec("Docs v1", McpAuthType.NONE)),
                McpEndpoint.class);
        McpEndpoint updated = write(
                "mcp/endpoint/update",
                "update-docs",
                created.revision(),
                new McpRpcContracts.EndpointWritePayload("docs", httpsSpec("Docs v2", McpAuthType.NONE)),
                McpEndpoint.class);
        McpEndpoint enabled = write(
                "mcp/endpoint/enable",
                "enable-docs",
                updated.revision(),
                new McpRpcContracts.EndpointQuery("docs"),
                McpEndpoint.class);

        McpRpcContracts.EndpointListResult listed = decode(
                invoke("mcp/endpoint/list", new McpRpcContracts.WorkspaceQuery(workspace.id())),
                McpRpcContracts.EndpointListResult.class);
        McpEndpoint read =
                decode(invoke("mcp/endpoint/read", new McpRpcContracts.EndpointQuery("docs")), McpEndpoint.class);
        McpHealth unknown =
                decode(invoke("mcp/health/read", new McpRpcContracts.EndpointQuery("docs")), McpHealth.class);
        McpHealth healthy =
                decode(invoke("mcp/health/probe", new McpRpcContracts.EndpointQuery("docs")), McpHealth.class);

        assertEquals(List.of(enabled), listed.endpoints());
        assertEquals(enabled, read);
        assertEquals(McpHealthState.UNKNOWN, unknown.state());
        assertEquals(McpHealthState.HEALTHY, healthy.state());
        assertExternalDataRoutes();

        remote.pages.add(new McpCatalogPage(List.of(tool("search")), Optional.empty()));
        McpEndpoint refreshed = write(
                "mcp/catalog/refresh",
                "refresh-docs",
                enabled.revision(),
                new McpRpcContracts.EndpointQuery("docs"),
                McpEndpoint.class);
        McpRpcContracts.CatalogRefreshResult refresh = decode(
                invoke("mcp/catalog/refresh/read", new McpRpcContracts.EndpointQuery("docs")),
                McpRpcContracts.CatalogRefreshResult.class);
        McpRpcContracts.CatalogResult catalog = decode(
                invoke(
                        "mcp/catalog/list",
                        new McpRpcContracts.CatalogQuery("docs", Optional.empty(), Optional.empty(), 10)),
                McpRpcContracts.CatalogResult.class);

        assertEquals(1, refreshed.catalogRevision());
        assertTrue(refresh.refresh().isPresent());
        assertEquals(List.of(tool("search")), catalog.page().entries());
    }

    private void assertExternalDataRoutes() {
        McpRpcContracts.ResourcePageResult resources = decode(
                invoke("mcp/resource/list", new McpRpcContracts.ExternalPageQuery("docs", Optional.empty())),
                McpRpcContracts.ResourcePageResult.class);
        McpRpcContracts.ResourceReadResult resource = decode(
                invoke("mcp/resource/read", new McpRpcContracts.ResourceReadQuery("docs", "docs://guide")),
                McpRpcContracts.ResourceReadResult.class);
        McpRpcContracts.PromptPageResult prompts = decode(
                invoke("mcp/prompt/list", new McpRpcContracts.ExternalPageQuery("docs", Optional.empty())),
                McpRpcContracts.PromptPageResult.class);
        McpRpcContracts.PromptResult prompt = decode(
                invoke("mcp/prompt/get", new McpRpcContracts.PromptGetQuery("docs", "review", Map.of("topic", "v5"))),
                McpRpcContracts.PromptResult.class);
        assertEquals("docs://guide", resources.page().resources().getFirst().uri());
        assertEquals("hello", resource.resource().contents().getFirst().text().orElseThrow());
        assertEquals("review", prompts.page().prompts().getFirst().name());
        assertEquals(McpSamplingRole.USER, prompt.prompt().messages().getFirst().role());
    }

    @Test
    void 历史停用OAuth与Bundle失败也经过强类型路由() {
        McpEndpoint created = write(
                "mcp/endpoint/create",
                "create-oauth",
                0,
                new McpRpcContracts.EndpointWritePayload("oauth", httpsSpec("OAuth", McpAuthType.OAUTH_2_1_PKCE)),
                McpEndpoint.class);
        McpRpcContracts.OAuthResult latest = decode(
                invoke("mcp/oauth/read", McpRpcContracts.OAuthQuery.endpoint(created.id())),
                McpRpcContracts.OAuthResult.class);
        assertTrue(latest.authorization().isEmpty());
        assertEquals(
                ProtocolErrorCode.INVALID_PARAMS,
                error("mcp/oauth/read", McpRpcContracts.OAuthQuery.authorization("unknown-auth")));

        JsonRpcResponse start = invoke(
                "mcp/oauth/start",
                command("start-oauth", created.revision(), new McpRpcContracts.OAuthStartPayload(created.id())));
        JsonRpcResponse cancelMissing = invoke(
                "mcp/oauth/cancel", command("cancel-without-id", 0, McpRpcContracts.OAuthQuery.endpoint(created.id())));
        JsonRpcResponse cancelUnknown = invoke(
                "mcp/oauth/cancel",
                command("cancel-unknown", 0, McpRpcContracts.OAuthQuery.authorization("unknown-auth")));
        JsonRpcResponse bundle = invoke(
                "mcp/stdio/register",
                command(
                        "register-missing-bundle",
                        0,
                        new McpRpcContracts.SignedBundleRegisterPayload(
                                "stdio", workspace.id(), "missing.bundle", "Missing", Duration.ofSeconds(10))));

        assertTrue(start.error().isPresent());
        assertEquals(
                ProtocolErrorCode.INVALID_PARAMS,
                cancelMissing.error().orElseThrow().code());
        assertEquals(
                ProtocolErrorCode.INVALID_PARAMS,
                cancelUnknown.error().orElseThrow().code());
        assertTrue(bundle.error().isPresent());

        McpEndpoint disabled = write(
                "mcp/endpoint/disable",
                "disable-oauth",
                created.revision(),
                new McpRpcContracts.EndpointQuery(created.id()),
                McpEndpoint.class);
        McpRpcContracts.EndpointListResult history = decode(
                invoke("mcp/endpoint/history", new McpRpcContracts.EndpointQuery(created.id())),
                McpRpcContracts.EndpointListResult.class);
        assertEquals(McpEndpointState.DISABLED, disabled.state());
        assertEquals(2, history.endpoints().size());

        assertMcpDisableBlocksProbe(created.id());
    }

    private void assertMcpDisableBlocksProbe(String endpointId) {
        write(
                "extension/builtin/disable",
                "disable-mcp-platform",
                1,
                new BuiltinExtensionRpcContracts.ExtensionPayload(BuiltinExtensionIds.MCP),
                BuiltinExtensionRpcContracts.StatusResult.class);
        assertTrue(invoke("mcp/health/probe", new McpRpcContracts.EndpointQuery(endpointId))
                .error()
                .isPresent());
    }

    private McpEndpointSpec httpsSpec(String name, McpAuthType authType) {
        return new McpEndpointSpec(
                workspace.id(),
                name,
                McpTransport.STREAMABLE_HTTPS,
                Optional.of(URI.create("https://mcp.example/rpc")),
                Optional.empty(),
                authType,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Duration.ofSeconds(30));
    }

    private McpCatalogEntry tool(String name) {
        return McpCatalogEntry.tool(
                name,
                Optional.of("Search"),
                "检索资料",
                components.json().parse("{\"type\":\"object\"}"),
                components.json().parse("{\"type\":\"object\"}"));
    }

    private InitializeParams initialization() {
        return new InitializeParams(
                ProtocolVersion.CURRENT,
                new ClientInfo("mcp-rpc-test", "5.0"),
                new CapabilityAdvertisement(StableCapabilities.withMcp(), Set.of()));
    }

    private McpRuntimePorts runtimePorts(FakeRemote fakeRemote) {
        return new McpRuntimePorts(
                fakeRemote,
                (turnId, workspaceId, endpoint) -> rejectingInteractions(),
                endpoint -> {},
                new RejectingOAuth(),
                true);
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

    private <T> T write(String method, String key, long revision, Object payload, Class<T> type) {
        return decode(invoke(method, command(key, revision, payload)), type);
    }

    private WriteCommand command(String key, long revision, Object payload) {
        return new WriteCommand(key, revision, components.json().encode(payload));
    }

    private int error(String method, Object params) {
        return invoke(method, params).error().orElseThrow().code();
    }

    private JsonRpcResponse invoke(String method, Object params) {
        return session.handle(new JsonRpcRequest(
                new RpcId(method + "-request"), method, components.json().encode(params)));
    }

    private <T> T decode(JsonRpcResponse response, Class<T> type) {
        return components.json().decode(response.result().orElseThrow(), type);
    }

    private static final class FakeRemote implements McpRemotePort {
        private final Queue<McpCatalogPage> pages = new ArrayDeque<>();

        @Override
        public McpRemoteSession initialize(
                McpEndpoint endpoint, String requiredProtocol, CancellationToken cancellation) {
            return new McpRemoteSession(McpProtocol.VERSION, Set.of("tools"));
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
                    List.of(new McpPromptMessage(McpSamplingRole.USER, new CanonicalPayload("{\"type\":\"text\"}"))),
                    List.of());
        }

        @Override
        public McpInvocationResult invoke(
                McpEndpoint endpoint,
                McpInvocationRequest request,
                McpClientInteractionPort interactions,
                CancellationToken cancellation) {
            throw new UnsupportedOperationException("测试不执行远端 Tool");
        }
    }

    private static final class RejectingOAuth implements McpOAuthBrokerPort {
        @Override
        public McpOAuthAuthorizationRequest authorizationRequest(
                McpEndpoint endpoint, String codeChallenge, String state, URI redirectUri) {
            throw new UnsupportedOperationException("测试未装配 OAuth Browser");
        }

        @Override
        public byte[] exchange(McpOAuthExchange exchange) {
            throw new UnsupportedOperationException("测试未装配 OAuth token 交换");
        }
    }

    private static final class NoOpModel implements ModelGateway {
        @Override
        public ModelCapabilities capabilities(String modelId) {
            return new ModelCapabilities(false, false, false, false, false, false, false);
        }

        @Override
        public ModelInvocationResult invoke(
                TurnId turnId, ModelInvocation invocation, ModelEventSink events, CancellationToken cancellation) {
            return new ModelInvocationResult(
                    "", List.of(), ModelUsage.zero(), Optional.empty(), Optional.empty(), ModelFinishReason.COMPLETE);
        }
    }
}
