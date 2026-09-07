package com.javaclaw.server.extension;

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
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.javaclaw.api.ApprovalRequirement;
import com.javaclaw.api.BrokerRequest;
import com.javaclaw.api.CancellationSource;
import com.javaclaw.api.CanonicalPayload;
import com.javaclaw.api.CredentialMetadata;
import com.javaclaw.api.FilePermission;
import com.javaclaw.api.NetworkPermission;
import com.javaclaw.api.PermissionProfile;
import com.javaclaw.api.ProcessPermission;
import com.javaclaw.api.ResourceLimits;
import com.javaclaw.api.ToolPermission;
import com.javaclaw.api.ToolRisk;
import com.javaclaw.api.WorkspaceId;
import com.javaclaw.browser.client.BrowserNetworkExchange;
import com.javaclaw.browser.client.BrowserStorageHandler;
import com.javaclaw.browser.client.BrowserWorkerPort;
import com.javaclaw.browser.protocol.BrowserWorkerProtocol;
import com.javaclaw.builtin.contracts.BuiltinExtensionIds;
import com.javaclaw.builtin.contracts.SiteContracts;
import com.javaclaw.extension.spi.ExtensionId;
import com.javaclaw.extension.spi.IsolatedServiceInvocation;
import com.javaclaw.nativehost.credential.MasterKeyProtector;
import com.javaclaw.protocol.CanonicalJson;
import com.javaclaw.server.persistence.CommandIdentity;
import com.javaclaw.server.persistence.H2Database;
import com.javaclaw.server.persistence.H2ManagedExtensionStore;
import com.javaclaw.server.security.BrowserBrokerResponse;
import com.javaclaw.server.security.grant.PrivateNetworkGrantService;
import com.javaclaw.server.security.vault.SecretVaultService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SiteBrowserServiceTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-01T08:00:00Z"), ZoneOffset.UTC);
    private static final URI ORIGIN = URI.create("https://docs.example.com");
    private static final URI ASSET_ORIGIN = URI.create("https://assets.example.com");
    private static final WorkspaceId WORKSPACE = WorkspaceId.parse("f199f4f0-10b2-4d48-9941-d44571acf8d4");

    @TempDir
    Path temporaryDirectory;

    @Test
    void 主Origin覆盖Worker凭据而跨Origin永不携带Secret() throws Exception {
        try (Fixture fixture = fixture()) {
            CredentialMetadata credential = fixture.vault()
                    .create(
                            identity("credential/create"),
                            SiteContracts.SITE_CREDENTIAL_NAMESPACE,
                            "site-token".getBytes(StandardCharsets.UTF_8));
            SiteContracts.Site site = site(
                    1,
                    1,
                    new SiteContracts.SiteCredential(
                            SiteContracts.CredentialKind.BEARER, Optional.of(credential.reference()), Optional.empty()),
                    true);
            fixture.put(site, 0);

            fixture.browser().networkRequests = List.of(
                    new BrowserWorkerProtocol.NetworkRequest(
                            URI.create("https://docs.example.com/page"),
                            "GET",
                            Map.of("authorization", List.of("Bearer worker-value"), "x-page", List.of("one"))),
                    new BrowserWorkerProtocol.NetworkRequest(
                            URI.create("https://assets.example.com/app.js"), "GET", Map.of()));

            CanonicalPayload result = fixture.service().snapshot(invocation(fixture, site));

            assertEquals(
                    "JavaClaw",
                    fixture.json()
                            .decode(result, SiteContracts.PageSnapshot.class)
                            .title());
            assertEquals(
                    List.of("Bearer site-token"),
                    fixture.broker().requests.get(0).headers().get("authorization"));
            assertEquals(
                    List.of("one"), fixture.broker().requests.get(0).headers().get("x-page"));
            assertFalse(fixture.broker().requests.get(1).headers().containsKey("authorization"));
        }
    }

    @Test
    void DNS固定后Site撤权会在打开Socket前失败() throws Exception {
        try (Fixture fixture = fixture()) {
            SiteContracts.Site frozen = site(1, 1, SiteContracts.SiteCredential.none(), true);
            fixture.put(frozen, 0);
            fixture.browser().networkRequests = List.of(request("https://docs.example.com/page"));
            fixture.broker().beforeRealtime = () -> {
                try {
                    fixture.put(site(2, 2, SiteContracts.SiteCredential.none(), false), 1);
                } catch (Exception failure) {
                    throw new IllegalStateException(failure);
                }
            };

            assertThrows(SecurityException.class, () -> fixture.service().snapshot(invocation(fixture, frozen)));
            assertFalse(fixture.broker().socketOpened);
        }
    }

    @Test
    void BrowserStorage只在Vault回调内存在且不进入结果() throws Exception {
        String marker = "browser-storage-marker";
        try (Fixture fixture = fixture()) {
            CredentialMetadata credential = fixture.vault()
                    .create(
                            identity("credential/create"),
                            SiteContracts.BROWSER_CREDENTIAL_NAMESPACE,
                            marker.getBytes(StandardCharsets.UTF_8));
            SiteContracts.Site site = site(
                    1,
                    1,
                    new SiteContracts.SiteCredential(
                            SiteContracts.CredentialKind.BROWSER_STORAGE,
                            Optional.of(credential.reference()),
                            Optional.empty()),
                    true);
            fixture.put(site, 0);

            CanonicalPayload result = fixture.service().snapshot(invocation(fixture, site));

            assertTrue(allZero(fixture.browser().observedStorage.get()));
            assertFalse(result.json().contains(marker));
            assertTrue(fixture.broker().requests.isEmpty());
        }
    }

    @Test
    void 人工登录状态只返回脱敏投影且保存后原子切换SiteAuthority() throws Exception {
        try (Fixture fixture = fixture()) {
            SiteContracts.Site site = site(1, 1, SiteContracts.SiteCredential.none(), true);
            fixture.put(site, 0);
            String sessionId = UUID.randomUUID().toString();
            SiteContracts.LoginBeginTask beginTask =
                    new SiteContracts.LoginBeginTask(site, sessionId, Duration.ofMinutes(10));

            SiteContracts.LoginSession ready = fixture.json()
                    .decode(
                            fixture.service().loginBegin(invocation(fixture, beginTask)),
                            SiteContracts.LoginSession.class);
            SiteContracts.LoginSaveTask saveTask =
                    new SiteContracts.LoginSaveTask(sessionId, "login-save", "a".repeat(64));
            CanonicalPayload payload = fixture.service().loginSave(invocation(fixture, saveTask));
            SiteContracts.LoginSaveCommit saved = fixture.json().decode(payload, SiteContracts.LoginSaveCommit.class);

            assertEquals(SiteContracts.LoginSessionState.READY, ready.state());
            assertEquals(2, saved.site().revision());
            assertEquals(2, saved.site().authorityRevision());
            assertEquals(
                    SiteContracts.CredentialKind.BROWSER_STORAGE,
                    saved.site().credential().kind());
            assertEquals(SiteContracts.LoginSessionState.SAVED, saved.session().state());
            assertFalse(payload.json().contains("browser-login-secret"));
            fixture.vault().use(saved.credential().reference(), state -> {
                assertEquals("browser-login-secret", new String(state, StandardCharsets.UTF_8));
                return null;
            });

            CanonicalPayload replay = fixture.service().loginSave(invocation(fixture, saveTask));
            assertEquals(saved, fixture.json().decode(replay, SiteContracts.LoginSaveCommit.class));
        }
    }

    @Test
    void Browser运行时不可用与未验证交互能力均失败关闭() throws Exception {
        CanonicalJson json = new CanonicalJson();
        SiteContracts.Site site = site(1, 1, SiteContracts.SiteCredential.none(), true);
        SiteContracts.LoginBeginTask begin =
                new SiteContracts.LoginBeginTask(site, UUID.randomUUID().toString(), Duration.ofMinutes(1));
        try (SiteBrowserService service = SiteBrowserService.unavailable(json)) {
            SiteContracts.SnapshotTask snapshot = new SiteContracts.SnapshotTask(
                    site, URI.create("https://docs.example.com/page"), 2_000, Duration.ofSeconds(10));
            assertFalse(service.isAvailable());
            assertThrows(IllegalStateException.class, () -> service.snapshot(invocation(json, WORKSPACE, snapshot)));
            SiteContracts.LoginSessionList sessions = json.decode(
                    service.loginList(invocation(json, WORKSPACE, Map.of())), SiteContracts.LoginSessionList.class);
            assertTrue(sessions.sessions().isEmpty());
            assertFalse(sessions.interactiveLoginAvailable());
            assertThrows(IllegalStateException.class, () -> service.loginBegin(invocation(json, WORKSPACE, begin)));
            service.invalidate(invocation(
                    json, WORKSPACE, new SiteContracts.AuthorityInvalidation(site.id(), site.authorityRevision())));
        }

        try (Fixture fixture = fixture()) {
            fixture.browser().interactiveLogin = false;
            SiteContracts.LoginSessionList sessions = loginList(fixture);
            assertFalse(sessions.interactiveLoginAvailable());
            assertThrows(
                    UnsupportedOperationException.class,
                    () -> fixture.service().loginBegin(invocation(fixture, begin)));
        }
    }

    @Test
    void 登录控制绑定Workspace并清理Worker中已消失的会话() throws Exception {
        try (Fixture fixture = fixture()) {
            SiteContracts.Site original = site(1, 1, SiteContracts.SiteCredential.none(), true);
            fixture.put(original, 0);
            String sessionId = UUID.randomUUID().toString();
            fixture.service()
                    .loginBegin(invocation(
                            fixture, new SiteContracts.LoginBeginTask(original, sessionId, Duration.ofMinutes(1))));
            SiteContracts.LoginControlRequest control = new SiteContracts.LoginControlRequest(sessionId);
            SiteContracts.LoginSession status = fixture.json()
                    .decode(
                            fixture.service().loginStatus(invocation(fixture, control)),
                            SiteContracts.LoginSession.class);
            SiteContracts.LoginSessionList listed = loginList(fixture);
            assertEquals(SiteContracts.LoginSessionState.READY, status.state());
            assertEquals(List.of(status), listed.sessions());
            SiteContracts.LoginSession cancelled = fixture.json()
                    .decode(
                            fixture.service().loginCancel(invocation(fixture, control)),
                            SiteContracts.LoginSession.class);
            assertEquals(SiteContracts.LoginSessionState.CANCELLED, cancelled.state());
            WorkspaceId other = WorkspaceId.parse("5ab755c5-5f1b-4129-bd73-1ee5831a7dc2");
            assertThrows(
                    IllegalArgumentException.class,
                    () -> fixture.service().loginStatus(invocation(fixture.json(), other, control)));
            SiteContracts.Site changed = site(2, 2, SiteContracts.SiteCredential.none(), true);
            fixture.put(changed, 1);
            assertThrows(
                    IllegalArgumentException.class,
                    () -> fixture.service()
                            .loginBegin(invocation(
                                    fixture,
                                    new SiteContracts.LoginBeginTask(changed, sessionId, Duration.ofMinutes(1)))));
            fixture.browser().loginSessions.remove(sessionId);
            SiteContracts.LoginSessionList cleaned = loginList(fixture);
            assertTrue(cleaned.sessions().isEmpty());
        }
    }

    @Test
    void 自定义凭据头仅注入主Origin且非法凭据在Broker前拒绝() throws Exception {
        try (Fixture fixture = fixture()) {
            CredentialMetadata credential = fixture.vault()
                    .create(
                            identity("credential/create"),
                            SiteContracts.SITE_CREDENTIAL_NAMESPACE,
                            "api-secret".getBytes(StandardCharsets.UTF_8));
            SiteContracts.Site site = site(
                    1,
                    1,
                    new SiteContracts.SiteCredential(
                            SiteContracts.CredentialKind.API_KEY_HEADER,
                            Optional.of(credential.reference()),
                            Optional.of("x-api-key")),
                    true);
            fixture.put(site, 0);
            fixture.browser().networkRequests = List.of(new BrowserWorkerProtocol.NetworkRequest(
                    URI.create("https://docs.example.com/page"),
                    "GET",
                    Map.of("host", List.of("worker"), "x-client", List.of("one"))));
            fixture.service().snapshot(invocation(fixture, site));
            assertEquals(
                    List.of("api-secret"),
                    fixture.broker().requests.getFirst().headers().get("x-api-key"));
            assertFalse(fixture.broker().requests.getFirst().headers().containsKey("host"));
            fixture.browser().networkRequests = List.of(request("https://outside.example.com/page"));
            assertThrows(SecurityException.class, () -> fixture.service().snapshot(invocation(fixture, site)));

            long revision = 2;
            for (byte[] invalid : List.of(new byte[] {1}, new byte[16 * 1024 + 1])) {
                CredentialMetadata rejected = fixture.vault()
                        .create(identity("credential/create"), SiteContracts.SITE_CREDENTIAL_NAMESPACE, invalid);
                SiteContracts.Site changed = site(
                        revision,
                        revision,
                        new SiteContracts.SiteCredential(
                                SiteContracts.CredentialKind.BEARER,
                                Optional.of(rejected.reference()),
                                Optional.empty()),
                        true);
                fixture.put(changed, revision - 1);
                fixture.browser().networkRequests = List.of(request("https://docs.example.com/page"));
                assertThrows(SecurityException.class, () -> fixture.service().snapshot(invocation(fixture, changed)));
                revision++;
            }
            assertEquals(1, fixture.broker().requests.size());
        }
    }

    private Fixture fixture() {
        CanonicalJson json = new CanonicalJson();
        H2Database database = new H2Database(temporaryDirectory.resolve("data-v6"));
        database.initialize();
        H2ManagedExtensionStore documents = new H2ManagedExtensionStore(database, CLOCK);
        SecretVaultService vault =
                new SecretVaultService(database, new MemoryProtector(), json, CLOCK, new SecureRandom());
        FakeBrowser browser = new FakeBrowser(json);
        FakeBroker broker = new FakeBroker();
        SiteBrowserService service = SiteBrowserService.available(
                browser, documents, vault, broker, new PrivateNetworkGrantService(database, json, CLOCK), json);
        return new Fixture(json, documents, vault, browser, broker, service);
    }

    private static IsolatedServiceInvocation invocation(Fixture fixture, SiteContracts.Site site) {
        SiteContracts.SnapshotTask task = new SiteContracts.SnapshotTask(
                site, URI.create("https://docs.example.com/page"), 2_000, Duration.ofSeconds(10));
        return new IsolatedServiceInvocation(
                new ExtensionId(BuiltinExtensionIds.SITE),
                WORKSPACE,
                permission(),
                SiteContracts.BROWSER_SNAPSHOT_SERVICE,
                fixture.json().encode(task),
                new CancellationSource());
    }

    private static IsolatedServiceInvocation invocation(Fixture fixture, Object request) {
        return invocation(fixture.json(), WORKSPACE, request);
    }

    private static IsolatedServiceInvocation invocation(CanonicalJson json, WorkspaceId workspaceId, Object request) {
        return new IsolatedServiceInvocation(
                new ExtensionId(BuiltinExtensionIds.SITE),
                workspaceId,
                permission(),
                "browser-test",
                json.encode(request),
                new CancellationSource());
    }

    private static SiteContracts.LoginSessionList loginList(Fixture fixture) {
        return fixture.json()
                .decode(
                        fixture.service().loginList(invocation(fixture, Map.of())),
                        SiteContracts.LoginSessionList.class);
    }

    private static SiteContracts.Site site(
            long revision, long authorityRevision, SiteContracts.SiteCredential credential, boolean enabled) {
        return new SiteContracts.Site(
                "docs",
                revision,
                authorityRevision,
                "Docs",
                ORIGIN,
                Set.of(ORIGIN, ASSET_ORIGIN),
                credential,
                Optional.empty(),
                enabled,
                CLOCK.instant());
    }

    private static BrowserWorkerProtocol.NetworkRequest request(String uri) {
        return new BrowserWorkerProtocol.NetworkRequest(URI.create(uri), "GET", Map.of());
    }

    private static PermissionProfile permission() {
        return new PermissionProfile(
                "site-test",
                1,
                new FilePermission(List.of(), List.of(), false, false),
                new NetworkPermission(Set.of("docs.example.com", "assets.example.com"), Set.of(443), true),
                new ProcessPermission(Set.of(), false, Duration.ofSeconds(30)),
                new ToolPermission(Set.of(), ToolRisk.READ_ONLY, ApprovalRequirement.NONE),
                new ResourceLimits(64 * 1024 * 1024, 8 * 1024 * 1024, 1, 16));
    }

    private static CommandIdentity identity(String method) {
        return new CommandIdentity(method, UUID.randomUUID().toString(), 0, "0".repeat(64));
    }

    private static boolean allZero(byte[] value) {
        return value != null && Arrays.equals(value, new byte[value.length]);
    }

    private record Fixture(
            CanonicalJson json,
            H2ManagedExtensionStore documents,
            SecretVaultService vault,
            FakeBrowser browser,
            FakeBroker broker,
            SiteBrowserService service)
            implements AutoCloseable {
        private void put(SiteContracts.Site site, long expectedRevision) throws Exception {
            documents.inTransaction(
                    new ExtensionId(BuiltinExtensionIds.SITE),
                    transaction ->
                            transaction.put("documents." + WORKSPACE, site.id(), expectedRevision, json.encode(site)));
        }

        @Override
        public void close() {
            service.close();
            vault.close();
        }
    }

    private static final class FakeBrowser implements BrowserWorkerPort {
        private final CanonicalJson json;
        private final AtomicReference<byte[]> observedStorage = new AtomicReference<>();
        private List<BrowserWorkerProtocol.NetworkRequest> networkRequests = List.of();
        private final Map<String, SiteContracts.LoginSession> loginSessions = new HashMap<>();
        private boolean interactiveLogin = true;

        private FakeBrowser(CanonicalJson json) {
            this.json = json;
        }

        @Override
        public CanonicalPayload snapshot(
                SiteContracts.SnapshotTask task,
                byte[] storageState,
                BrowserNetworkExchange network,
                com.javaclaw.api.CancellationToken cancellation) {
            observedStorage.set(storageState);
            try {
                for (BrowserWorkerProtocol.NetworkRequest request : networkRequests) {
                    network.exchange(request, new byte[0], cancellation);
                }
            } catch (RuntimeException failure) {
                throw failure;
            } catch (Exception failure) {
                throw new IllegalStateException(failure);
            }
            return json.encode(new SiteContracts.PageSnapshot(task.uri(), "JavaClaw", "safe", CLOCK.instant()));
        }

        @Override
        public void invalidate(String siteId, long currentAuthorityRevision) {}

        @Override
        public SiteContracts.LoginSession beginLogin(
                SiteContracts.LoginBeginTask task,
                byte[] storageState,
                BrowserNetworkExchange network,
                com.javaclaw.api.CancellationToken cancellation) {
            SiteContracts.LoginSession session = new SiteContracts.LoginSession(
                    task.sessionId(),
                    task.site().id(),
                    task.site().revision(),
                    task.site().authorityRevision(),
                    SiteContracts.LoginSessionState.READY,
                    CLOCK.instant(),
                    CLOCK.instant().plus(task.timeout()),
                    Optional.empty());
            loginSessions.put(task.sessionId(), session);
            return session;
        }

        @Override
        public SiteContracts.LoginSession loginStatus(String sessionId) {
            SiteContracts.LoginSession session = loginSessions.get(sessionId);
            if (session == null) {
                throw new IllegalArgumentException("missing login session");
            }
            return session;
        }

        @Override
        public <T> T saveLogin(String sessionId, BrowserStorageHandler<T> handler) {
            SiteContracts.LoginSession current = loginStatus(sessionId);
            byte[] state = "browser-login-secret".getBytes(StandardCharsets.UTF_8);
            try {
                T result = handler.handle(state);
                loginSessions.put(
                        sessionId,
                        new SiteContracts.LoginSession(
                                current.sessionId(),
                                current.siteId(),
                                current.siteRevision(),
                                current.authorityRevision(),
                                SiteContracts.LoginSessionState.SAVED,
                                current.startedAt(),
                                current.expiresAt(),
                                Optional.empty()));
                return result;
            } catch (RuntimeException failure) {
                throw failure;
            } catch (Exception failure) {
                throw new IllegalStateException(failure);
            } finally {
                Arrays.fill(state, (byte) 0);
            }
        }

        @Override
        public SiteContracts.LoginSession cancelLogin(String sessionId) {
            SiteContracts.LoginSession current = loginStatus(sessionId);
            SiteContracts.LoginSession cancelled = new SiteContracts.LoginSession(
                    current.sessionId(),
                    current.siteId(),
                    current.siteRevision(),
                    current.authorityRevision(),
                    SiteContracts.LoginSessionState.CANCELLED,
                    current.startedAt(),
                    current.expiresAt(),
                    Optional.empty());
            loginSessions.put(sessionId, cancelled);
            return cancelled;
        }

        @Override
        public boolean interactiveLoginAvailable() {
            return interactiveLogin;
        }

        @Override
        public com.javaclaw.browser.client.McpOAuthBrowserSession beginOAuth(
                com.javaclaw.browser.client.McpOAuthBrowserTask task,
                BrowserNetworkExchange network,
                com.javaclaw.browser.client.McpOAuthCallbackHandler callback,
                com.javaclaw.api.CancellationToken cancellation) {
            throw new UnsupportedOperationException();
        }

        @Override
        public com.javaclaw.browser.client.McpOAuthBrowserSession oauthStatus(String sessionId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public com.javaclaw.browser.client.McpOAuthBrowserSession cancelOAuth(String sessionId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void invalidateOAuth(String endpointId, long currentRevision) {}

        @Override
        public boolean oauthAvailable() {
            return false;
        }

        @Override
        public void close() {}
    }

    private static final class FakeBroker implements SiteNetworkBroker {
        private final java.util.ArrayList<BrokerRequest> requests = new java.util.ArrayList<>();
        private Runnable beforeRealtime = () -> {};
        private boolean socketOpened;

        @Override
        public BrowserBrokerResponse exchange(
                BrokerRequest request,
                PermissionProfile permission,
                com.javaclaw.api.CancellationToken cancellation,
                com.javaclaw.server.security.PinnedHttpNetworkBroker.AddressAuthorization privateAuthorization,
                com.javaclaw.server.security.PinnedHttpNetworkBroker.RealtimeAuthorization realtimeAuthorization)
                throws Exception {
            requests.add(request);
            beforeRealtime.run();
            realtimeAuthorization.authorize();
            socketOpened = true;
            return new BrowserBrokerResponse(200, Map.of(), "ok".getBytes(StandardCharsets.UTF_8), false);
        }
    }

    private static final class MemoryProtector implements MasterKeyProtector {
        private final Map<String, byte[]> keys = new HashMap<>();

        @Override
        public Optional<byte[]> load(String keyId) {
            return Optional.ofNullable(keys.get(keyId)).map(byte[]::clone);
        }

        @Override
        public void store(String keyId, byte[] key) {
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
