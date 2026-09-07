package com.javaclaw.server.mcp;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.javaclaw.api.CancellationToken;
import com.javaclaw.api.CanonicalPayload;
import com.javaclaw.api.McpAuthType;
import com.javaclaw.api.McpCatalogPage;
import com.javaclaw.api.McpEndpoint;
import com.javaclaw.api.McpEndpointSpec;
import com.javaclaw.api.McpEndpointState;
import com.javaclaw.api.McpInvocationRequest;
import com.javaclaw.api.McpInvocationResult;
import com.javaclaw.api.McpOAuthState;
import com.javaclaw.api.McpProtocol;
import com.javaclaw.api.McpRemoteSession;
import com.javaclaw.api.McpTransport;
import com.javaclaw.api.Workspace;
import com.javaclaw.browser.client.BrowserNetworkExchange;
import com.javaclaw.browser.client.BrowserStorageHandler;
import com.javaclaw.browser.client.BrowserWorkerPort;
import com.javaclaw.browser.client.McpOAuthBrowserSession;
import com.javaclaw.browser.client.McpOAuthBrowserState;
import com.javaclaw.browser.client.McpOAuthBrowserTask;
import com.javaclaw.browser.client.McpOAuthCallbackHandler;
import com.javaclaw.builtin.contracts.SiteContracts;
import com.javaclaw.extension.spi.McpClientInteractionPort;
import com.javaclaw.extension.spi.McpOAuthAuthorizationRequest;
import com.javaclaw.extension.spi.McpOAuthBrokerPort;
import com.javaclaw.extension.spi.McpOAuthExchange;
import com.javaclaw.extension.spi.McpRemotePort;
import com.javaclaw.nativehost.credential.MasterKeyProtectionException;
import com.javaclaw.nativehost.credential.MasterKeyProtector;
import com.javaclaw.protocol.CanonicalJson;
import com.javaclaw.protocol.McpRpcContracts;
import com.javaclaw.server.lifecycle.LifecycleCoordinator;
import com.javaclaw.server.persistence.CommandIdentity;
import com.javaclaw.server.persistence.CoreCommandService;
import com.javaclaw.server.persistence.ExtensionCatalogRepository;
import com.javaclaw.server.persistence.H2Database;
import com.javaclaw.server.persistence.LifecycleLeaseRepository;
import com.javaclaw.server.persistence.PersistenceException;
import com.javaclaw.server.security.grant.PrivateNetworkGrantService;
import com.javaclaw.server.security.vault.SecretVaultService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class McpOAuthServiceTest {
    private static final Instant NOW = Instant.parse("2026-09-01T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final URI REDIRECT = URI.create("http://127.0.0.1:17845/oauth/callback");

    @TempDir
    Path temporaryDirectory;

    private H2Database database;
    private CanonicalJson json;
    private Workspace workspace;
    private MemoryProtector protector;
    private SecretVaultService vault;
    private McpService mcp;
    private FakeOAuthBroker broker;
    private McpOAuthService service;

    @BeforeEach
    void 创建全新OAuth状态机() {
        database = new H2Database(temporaryDirectory.resolve("data-v6"));
        database.initialize();
        json = new CanonicalJson();
        CoreCommandService core = new CoreCommandService(database, json, CLOCK);
        workspace = core.createWorkspace(
                identity("workspace/create", "workspace-create", 0, "workspace"),
                "OAuth",
                temporaryDirectory.resolve("workspace"));
        protector = new MemoryProtector();
        vault = new SecretVaultService(database, protector, json, CLOCK, new SecureRandom());
        mcp = new McpService(
                database,
                new McpServiceDependencies(
                        new HealthyRemote(),
                        (turnId, workspaceId, endpoint) -> rejectingInteractions(),
                        ignored -> {},
                        reference -> vault.metadata(reference).isPresent(),
                        ignored -> {
                            throw new IllegalStateException("signed Bundle MCP unavailable");
                        }),
                json,
                CLOCK);
        broker = new FakeOAuthBroker();
        service = new McpOAuthService(database, mcp, vault, broker, REDIRECT, json, CLOCK);
    }

    @Test
    void 启动只返回脱敏投影且完成后原子绑定Vault凭据() {
        McpEndpoint endpoint = enabledOauthEndpoint("oauth-docs");
        CommandIdentity start = identity("mcp/oauth/start", "oauth-start", 0, endpoint.id());

        McpOAuthService.BrowserLaunch launch = service.start(start, endpoint.id());

        assertEquals(McpOAuthState.PENDING, launch.authorization().state());
        assertEquals("login.example.test", launch.authorization().authorizationHost());
        assertEquals(1, vault.status().credentialCount());
        String projection = json.encode(launch.authorization()).json();
        assertFalse(projection.contains("authorizationUri"));
        assertFalse(projection.contains("credential"));
        assertFalse(projection.contains(broker.state));
        assertTrue(launch.authorizationUri().toString().contains("code_challenge="));

        URI callback = URI.create(REDIRECT + "?code=one-time-code&state=" + broker.state);
        var completed = service.completeBrowser(launch.authorization().id(), callback);

        assertEquals(McpOAuthState.AUTHORIZED, completed.state());
        McpEndpoint authorized = mcp.requireLatest(endpoint.id());
        assertTrue(authorized.spec().credential().isPresent());
        assertEquals(1, vault.status().credentialCount());
        assertEquals(
                "oauth-token",
                vault.use(
                        authorized.spec().credential().orElseThrow(),
                        bytes -> new String(bytes, StandardCharsets.UTF_8)));
    }

    @Test
    void 取消和重启都会清理Pkce且旧Callback不可再用() {
        McpEndpoint endpoint = enabledOauthEndpoint("oauth-cancel");
        McpOAuthService.BrowserLaunch cancelledLaunch =
                service.start(identity("mcp/oauth/start", "cancel-start", 0, endpoint.id()), endpoint.id());

        var cancelled = service.cancel(
                identity(
                        "mcp/oauth/cancel",
                        "cancel-command",
                        0,
                        cancelledLaunch.authorization().id()),
                cancelledLaunch.authorization().id());

        assertEquals(McpOAuthState.CANCELLED, cancelled.state());
        assertEquals(0, vault.status().credentialCount());
        assertThrows(
                PersistenceException.class,
                () -> service.completeBrowser(
                        cancelled.id(), URI.create(REDIRECT + "?code=late&state=" + broker.state)));

        McpOAuthService.BrowserLaunch interrupted =
                service.start(identity("mcp/oauth/start", "restart-start", 0, endpoint.id()), endpoint.id());
        McpOAuthService restarted = new McpOAuthService(database, mcp, vault, broker, REDIRECT, json, CLOCK);

        var recovered = restarted.require(interrupted.authorization().id());
        assertEquals(McpOAuthState.FAILED, recovered.state());
        assertEquals(Optional.of("APP_SERVER_RESTARTED"), recovered.detail());
        assertEquals(0, vault.status().credentialCount());
    }

    @Test
    void EndpointRevision变化和Vault锁定都会实时拒绝() {
        McpEndpoint endpoint = enabledOauthEndpoint("oauth-revision");
        McpOAuthService.BrowserLaunch launch =
                service.start(identity("mcp/oauth/start", "revision-start", 0, endpoint.id()), endpoint.id());
        mcp.setState(
                identity("mcp/endpoint/disable", "disable", endpoint.revision(), endpoint.id()),
                endpoint.id(),
                McpEndpointState.DISABLED);

        assertThrows(
                PersistenceException.class,
                () -> service.completeBrowser(
                        launch.authorization().id(), URI.create(REDIRECT + "?code=stale&state=" + broker.state)));

        H2Database lockedDatabase = new H2Database(temporaryDirectory.resolve("locked-vault/data-v6"));
        lockedDatabase.initialize();
        MemoryProtector unavailable = new MemoryProtector();
        unavailable.unavailable = true;
        try (SecretVaultService locked =
                new SecretVaultService(lockedDatabase, unavailable, json, CLOCK, new SecureRandom())) {
            McpOAuthService lockedService =
                    new McpOAuthService(lockedDatabase, mcp, locked, broker, REDIRECT, json, CLOCK);
            assertThrows(
                    PersistenceException.class,
                    () -> lockedService.start(identity("mcp/oauth/start", "locked", 0, endpoint.id()), endpoint.id()));
        }
    }

    @Test
    void Coordinator私有回调完成Token交换并释放LifecycleLease() {
        McpEndpoint endpoint = enabledOauthEndpoint("oauth-browser");
        ExtensionCatalogRepository extensions = enabledMcpCatalog();
        FakeBrowserWorker browser = new FakeBrowserWorker(true);
        try (LifecycleCoordinator lifecycle =
                new LifecycleCoordinator(new LifecycleLeaseRepository(database, CLOCK), Duration.ofSeconds(5))) {
            McpOAuthCoordinator coordinator = new McpOAuthCoordinator(
                    service,
                    mcp,
                    vault,
                    new PrivateNetworkGrantService(database, json, CLOCK),
                    extensions,
                    lifecycle,
                    Optional.of(browser));

            var started = coordinator.start(
                    identity("mcp/oauth/start", "coordinator-start", 0, endpoint.id()), endpoint.id());

            assertEquals(McpOAuthState.PENDING, started.state());
            assertEquals(
                    McpOAuthState.AUTHORIZED, coordinator.require(started.id()).state());
            assertEquals(0, lifecycle.status().activeLeases());
            assertTrue(mcp.requireLatest(endpoint.id()).spec().credential().isPresent());
        }
    }

    @Test
    void Coordinator在Endpoint撤权后终止Worker并释放Lease() throws Exception {
        McpEndpoint endpoint = enabledOauthEndpoint("oauth-revoke");
        FakeBrowserWorker browser = new FakeBrowserWorker(false);
        try (LifecycleCoordinator lifecycle =
                new LifecycleCoordinator(new LifecycleLeaseRepository(database, CLOCK), Duration.ofSeconds(5))) {
            McpOAuthCoordinator coordinator = new McpOAuthCoordinator(
                    service,
                    mcp,
                    vault,
                    new PrivateNetworkGrantService(database, json, CLOCK),
                    enabledMcpCatalog(),
                    lifecycle,
                    Optional.of(browser));
            var started =
                    coordinator.start(identity("mcp/oauth/start", "revoke-start", 0, endpoint.id()), endpoint.id());
            assertEquals(1, lifecycle.status().activeLeases());

            mcp.setState(
                    identity("mcp/endpoint/disable", "revoke-disable", endpoint.revision(), endpoint.id()),
                    endpoint.id(),
                    McpEndpointState.DISABLED);

            awaitTerminal(coordinator, started.id(), lifecycle);
            assertEquals(McpOAuthState.FAILED, coordinator.require(started.id()).state());
            assertEquals(0, lifecycle.status().activeLeases());
            assertEquals(McpOAuthBrowserState.CANCELLED, browser.session.state());
        }
    }

    @Test
    void Coordinator缺少可信Browser时在写入Pkce前FailClosed() {
        McpEndpoint endpoint = enabledOauthEndpoint("oauth-no-browser");
        try (LifecycleCoordinator lifecycle =
                new LifecycleCoordinator(new LifecycleLeaseRepository(database, CLOCK), Duration.ofSeconds(5))) {
            McpOAuthCoordinator coordinator = new McpOAuthCoordinator(
                    service,
                    mcp,
                    vault,
                    new PrivateNetworkGrantService(database, json, CLOCK),
                    enabledMcpCatalog(),
                    lifecycle,
                    Optional.empty());

            assertThrows(
                    IllegalStateException.class,
                    () -> coordinator.start(
                            identity("mcp/oauth/start", "no-browser", 0, endpoint.id()), endpoint.id()));
            assertEquals(0, vault.status().credentialCount());
            assertEquals(0, lifecycle.status().activeLeases());
        }
    }

    @Test
    void Coordinator重复Start复用活动会话且Cancel保持单次终止() {
        McpEndpoint endpoint = enabledOauthEndpoint("oauth-idempotent");
        FakeBrowserWorker browser = new FakeBrowserWorker(false);
        try (LifecycleCoordinator lifecycle =
                new LifecycleCoordinator(new LifecycleLeaseRepository(database, CLOCK), Duration.ofSeconds(5))) {
            McpOAuthCoordinator coordinator = new McpOAuthCoordinator(
                    service,
                    mcp,
                    vault,
                    new PrivateNetworkGrantService(database, json, CLOCK),
                    enabledMcpCatalog(),
                    lifecycle,
                    Optional.of(browser));
            CommandIdentity start = identity("mcp/oauth/start", "idempotent-start", 0, endpoint.id());
            var first = coordinator.start(start, endpoint.id());
            var recovered = coordinator.start(start, endpoint.id());
            assertEquals(first, recovered);
            assertEquals(1, lifecycle.status().activeLeases());

            CommandIdentity cancel = identity("mcp/oauth/cancel", "idempotent-cancel", 0, first.id());
            assertEquals(
                    McpOAuthState.CANCELLED,
                    coordinator.cancel(cancel, first.id()).state());
            assertEquals(
                    McpOAuthState.CANCELLED,
                    coordinator.cancel(cancel, first.id()).state());
            assertEquals(0, lifecycle.status().activeLeases());
        }
    }

    @Test
    void 到期重启收口Expired且既有终态保持不变() {
        McpEndpoint endpoint = enabledOauthEndpoint("oauth-expired");
        var launch = service.start(identity("mcp/oauth/start", "expired-start", 0, endpoint.id()), endpoint.id());
        Clock afterExpiry = Clock.fixed(NOW.plus(McpOAuthService.LIFETIME).plusSeconds(1), ZoneOffset.UTC);

        McpOAuthService restarted = new McpOAuthService(database, mcp, vault, broker, REDIRECT, json, afterExpiry);

        assertEquals(
                McpOAuthState.EXPIRED,
                restarted.require(launch.authorization().id()).state());
        assertEquals(
                McpOAuthState.EXPIRED,
                restarted
                        .terminate(launch.authorization().id(), McpOAuthState.FAILED, Optional.of("ignored"))
                        .state());
        assertThrows(
                IllegalArgumentException.class,
                () -> restarted.terminate(launch.authorization().id(), McpOAuthState.AUTHORIZED, Optional.empty()));
        assertEquals(0, vault.status().credentialCount());
    }

    private ExtensionCatalogRepository enabledMcpCatalog() {
        ExtensionCatalogRepository extensions = new ExtensionCatalogRepository(database, json, CLOCK);
        extensions.installBuiltIn(McpBuiltinExtensionDescriptor.create());
        return extensions;
    }

    private static void awaitTerminal(
            McpOAuthCoordinator coordinator, String authorizationId, LifecycleCoordinator lifecycle) throws Exception {
        for (int attempt = 0; attempt < 100; attempt++) {
            boolean terminal = coordinator.require(authorizationId).state() != McpOAuthState.PENDING;
            if (terminal && lifecycle.status().activeLeases() == 0) {
                return;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("OAuth revocation did not reach terminal state");
    }

    private McpEndpoint enabledOauthEndpoint(String id) {
        McpEndpointSpec spec = new McpEndpointSpec(
                workspace.id(),
                "OAuth MCP",
                McpTransport.STREAMABLE_HTTPS,
                Optional.of(URI.create("https://mcp.example.test/rpc")),
                Optional.empty(),
                McpAuthType.OAUTH_2_1_PKCE,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Duration.ofSeconds(30));
        McpEndpoint created = mcp.createHttps(
                identity("mcp/endpoint/create", id + "-create", 0, new McpRpcContracts.EndpointWritePayload(id, spec)),
                id,
                spec);
        return mcp.setState(
                identity(
                        "mcp/endpoint/enable",
                        id + "-enable",
                        created.revision(),
                        new McpRpcContracts.EndpointQuery(id)),
                id,
                McpEndpointState.ENABLED);
    }

    private CommandIdentity identity(String method, String key, long revision, Object payload) {
        CanonicalPayload encoded = json.encode(new DigestInput(revision, payload));
        return new CommandIdentity(method, key, revision, encoded.sha256());
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

    private static final class HealthyRemote implements McpRemotePort {
        @Override
        public McpRemoteSession initialize(
                McpEndpoint endpoint, String requiredProtocol, CancellationToken cancellation) {
            return new McpRemoteSession(McpProtocol.VERSION, Set.of("tools"));
        }

        @Override
        public McpCatalogPage catalog(McpEndpoint endpoint, Optional<String> cursor, CancellationToken cancellation) {
            return new McpCatalogPage(List.of(), Optional.empty());
        }

        @Override
        public McpInvocationResult invoke(
                McpEndpoint endpoint,
                McpInvocationRequest request,
                McpClientInteractionPort interactions,
                CancellationToken cancellation) {
            throw new UnsupportedOperationException("not used");
        }
    }

    private static final class FakeOAuthBroker implements McpOAuthBrokerPort {
        private String state = "";

        @Override
        public McpOAuthAuthorizationRequest authorizationRequest(
                McpEndpoint endpoint, String codeChallenge, String requestedState, URI redirectUri) {
            state = requestedState;
            URI authorization = URI.create("https://login.example.test/authorize?code_challenge=" + codeChallenge
                    + "&state=" + requestedState);
            return new McpOAuthAuthorizationRequest(authorization, Set.of(URI.create("https://login.example.test")));
        }

        @Override
        public byte[] exchange(McpOAuthExchange exchange) {
            assertEquals(state, exchange.expectedState());
            assertTrue(exchange.callbackUri().getRawQuery().contains("state=" + state));
            return "oauth-token".getBytes(StandardCharsets.UTF_8);
        }
    }

    private static final class FakeBrowserWorker implements BrowserWorkerPort {
        private final boolean completeImmediately;
        private McpOAuthBrowserSession session;

        private FakeBrowserWorker(boolean completeImmediately) {
            this.completeImmediately = completeImmediately;
        }

        @Override
        public McpOAuthBrowserSession beginOAuth(
                McpOAuthBrowserTask task,
                BrowserNetworkExchange network,
                McpOAuthCallbackHandler callback,
                CancellationToken cancellation) {
            session = oauthSession(task, McpOAuthBrowserState.PENDING, Optional.empty());
            if (!completeImmediately) {
                return session;
            }
            try {
                String state = queryValue(task.authorizationUri(), "state");
                callback.handle(URI.create(task.redirectUri() + "?code=worker-private&state=" + state));
                session = oauthSession(task, McpOAuthBrowserState.COMPLETED, Optional.empty());
                return session;
            } catch (Exception failure) {
                session = oauthSession(task, McpOAuthBrowserState.FAILED, Optional.of("CALLBACK_FAILED"));
                throw new IllegalStateException("fake callback failed", failure);
            }
        }

        @Override
        public McpOAuthBrowserSession oauthStatus(String sessionId) {
            return session;
        }

        @Override
        public McpOAuthBrowserSession cancelOAuth(String sessionId) {
            session = new McpOAuthBrowserSession(
                    session.sessionId(),
                    session.authorizationId(),
                    session.endpointId(),
                    session.endpointRevision(),
                    McpOAuthBrowserState.CANCELLED,
                    session.startedAt(),
                    session.expiresAt(),
                    Optional.empty());
            return session;
        }

        @Override
        public void invalidateOAuth(String endpointId, long currentRevision) {
            cancelOAuth(session.sessionId());
        }

        @Override
        public boolean oauthAvailable() {
            return true;
        }

        @Override
        public CanonicalPayload snapshot(
                SiteContracts.SnapshotTask task,
                byte[] storageState,
                BrowserNetworkExchange network,
                CancellationToken cancellation) {
            throw new UnsupportedOperationException("not used");
        }

        @Override
        public SiteContracts.LoginSession beginLogin(
                SiteContracts.LoginBeginTask task,
                byte[] storageState,
                BrowserNetworkExchange network,
                CancellationToken cancellation) {
            throw new UnsupportedOperationException("not used");
        }

        @Override
        public SiteContracts.LoginSession loginStatus(String sessionId) {
            throw new UnsupportedOperationException("not used");
        }

        @Override
        public <T> T saveLogin(String sessionId, BrowserStorageHandler<T> handler) {
            throw new UnsupportedOperationException("not used");
        }

        @Override
        public SiteContracts.LoginSession cancelLogin(String sessionId) {
            throw new UnsupportedOperationException("not used");
        }

        @Override
        public boolean interactiveLoginAvailable() {
            return false;
        }

        @Override
        public void invalidate(String siteId, long currentAuthorityRevision) {}

        @Override
        public void close() {}

        private static McpOAuthBrowserSession oauthSession(
                McpOAuthBrowserTask task, McpOAuthBrowserState state, Optional<String> failureCode) {
            return new McpOAuthBrowserSession(
                    task.sessionId(),
                    task.authorizationId(),
                    task.endpointId(),
                    task.endpointRevision(),
                    state,
                    NOW,
                    NOW.plus(task.timeout()),
                    failureCode);
        }

        private static String queryValue(URI uri, String name) {
            for (String part : uri.getRawQuery().split("&")) {
                int separator = part.indexOf('=');
                if (separator > 0 && part.substring(0, separator).equals(name)) {
                    return part.substring(separator + 1);
                }
            }
            throw new IllegalArgumentException("missing query value");
        }
    }

    private static final class MemoryProtector implements MasterKeyProtector {
        private final Map<String, byte[]> keys = new HashMap<>();
        private boolean unavailable;

        @Override
        public Optional<byte[]> load(String keyId) {
            byte[] value = keys.get(keyId);
            return value == null ? Optional.empty() : Optional.of(value.clone());
        }

        @Override
        public void store(String keyId, byte[] key) {
            if (unavailable) {
                throw new MasterKeyProtectionException("test unavailable");
            }
            keys.put(keyId, key.clone());
        }

        @Override
        public void delete(String keyId) {
            byte[] removed = keys.remove(keyId);
            if (removed != null) {
                Arrays.fill(removed, (byte) 0);
            }
        }
    }
}
