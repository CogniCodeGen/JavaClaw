package com.javaclaw.browser.client;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.javaclaw.api.CancellationSource;
import com.javaclaw.api.CredentialRef;
import com.javaclaw.browser.protocol.BrowserWorkerProtocol;
import com.javaclaw.builtin.contracts.SiteContracts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BrowserSessionLifecycleTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void loginIsIdempotentOnlyForTheSameAuthorityAndCancellationIsTerminal() {
        String sessionId = java.util.UUID.randomUUID().toString();
        SiteContracts.LoginBeginTask task = loginTask(sessionId, site(1, 1, SiteContracts.SiteCredential.none()));
        try (BrowserWorkerClient client = loginClient("login-idempotent", null)) {
            SiteContracts.LoginSession ready =
                    client.beginLogin(task, new byte[0], network(), new CancellationSource());
            assertEquals(SiteContracts.LoginSessionState.READY, ready.state());
            assertEquals(ready, client.beginLogin(task, new byte[0], network(), new CancellationSource()));

            SiteContracts.LoginBeginTask changed =
                    loginTask(sessionId, site(2, 2, SiteContracts.SiteCredential.none()));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> client.beginLogin(changed, new byte[0], network(), new CancellationSource()));

            assertEquals(
                    SiteContracts.LoginSessionState.CANCELLED,
                    client.cancelLogin(sessionId).state());
            assertEquals(
                    SiteContracts.LoginSessionState.CANCELLED,
                    client.cancelLogin(sessionId).state());
            assertThrows(IllegalStateException.class, () -> client.saveLogin(sessionId, ignored -> null));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> client.loginStatus(java.util.UUID.randomUUID().toString()));
            assertThrows(IllegalArgumentException.class, () -> client.loginStatus("not-a-uuid"));
        }
    }

    @Test
    void loginEnforcesCredentialStateAndPersisterFailureBecomesStableTerminalState() {
        SiteContracts.SiteCredential browserCredential = new SiteContracts.SiteCredential(
                SiteContracts.CredentialKind.BROWSER_STORAGE,
                Optional.of(new CredentialRef(SiteContracts.BROWSER_CREDENTIAL_NAMESPACE, "state")),
                Optional.empty());
        try (BrowserWorkerClient client = loginClient("login-state", null)) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> client.beginLogin(
                            loginTask(
                                    java.util.UUID.randomUUID().toString(),
                                    site(1, 1, SiteContracts.SiteCredential.none())),
                            new byte[] {1},
                            network(),
                            new CancellationSource()));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> client.beginLogin(
                            loginTask(java.util.UUID.randomUUID().toString(), site(1, 1, browserCredential)),
                            new byte[BrowserWorkerProtocol.MAXIMUM_STATE_BYTES + 1],
                            network(),
                            new CancellationSource()));

            String sessionId = java.util.UUID.randomUUID().toString();
            client.beginLogin(
                    loginTask(sessionId, site(1, 1, browserCredential)),
                    new byte[0],
                    network(),
                    new CancellationSource());
            IllegalStateException failure = assertThrows(
                    IllegalStateException.class,
                    () -> client.saveLogin(sessionId, ignored -> {
                        throw new IllegalStateException("vault locked");
                    }));
            assertEquals("vault locked", failure.getMessage());
            assertEquals(
                    "STORAGE_PERSIST_FAILED",
                    client.loginStatus(sessionId).failureCode().orElseThrow());
        }

        try (BrowserWorkerClient client = loginClient("login-checked-state", null)) {
            String sessionId = java.util.UUID.randomUUID().toString();
            client.beginLogin(
                    loginTask(sessionId, site(1, 1, SiteContracts.SiteCredential.none())),
                    new byte[0],
                    network(),
                    new CancellationSource());
            assertThrows(
                    Exception.class,
                    () -> client.saveLogin(sessionId, ignored -> {
                        throw new Exception("vault unavailable");
                    }));
            assertEquals(
                    "STORAGE_PERSIST_FAILED",
                    client.loginStatus(sessionId).failureCode().orElseThrow());
        }
    }

    @Test
    void loginAuthorityRevocationAndCloseStopNewWork() {
        String oldSession = java.util.UUID.randomUUID().toString();
        String currentSession = java.util.UUID.randomUUID().toString();
        BrowserWorkerClient client = loginClient("login-invalidate", null);
        client.beginLogin(
                loginTask(oldSession, site(1, 1, SiteContracts.SiteCredential.none())),
                new byte[0],
                network(),
                new CancellationSource());
        client.beginLogin(
                loginTask(currentSession, site(2, 2, SiteContracts.SiteCredential.none())),
                new byte[0],
                network(),
                new CancellationSource());

        client.invalidate("docs", 2);

        assertEquals(
                SiteContracts.LoginSessionState.CANCELLED,
                client.loginStatus(oldSession).state());
        assertEquals(
                SiteContracts.LoginSessionState.READY,
                client.loginStatus(currentSession).state());
        client.close();
        assertThrows(
                IllegalStateException.class,
                () -> client.beginLogin(
                        loginTask(
                                java.util.UUID.randomUUID().toString(),
                                site(2, 2, SiteContracts.SiteCredential.none())),
                        new byte[0],
                        network(),
                        new CancellationSource()));
    }

    @Test
    void loginCapsConcurrentInteractiveWindows() {
        try (BrowserWorkerClient client = loginClient("login-limit", null)) {
            for (int index = 0; index < 4; index++) {
                client.beginLogin(
                        loginTask(
                                java.util.UUID.randomUUID().toString(),
                                site(1, 1, SiteContracts.SiteCredential.none())),
                        new byte[0],
                        network(),
                        new CancellationSource());
            }
            assertThrows(
                    IllegalStateException.class,
                    () -> client.beginLogin(
                            loginTask(
                                    java.util.UUID.randomUUID().toString(),
                                    site(1, 1, SiteContracts.SiteCredential.none())),
                            new byte[0],
                            network(),
                            new CancellationSource()));
        }
    }

    @Test
    void loginRejectsMalformedWorkerSequencesAndSensitiveResults() {
        for (String mode : List.of(
                "exit",
                "login-error-before-ready",
                "login-network-sequence",
                "login-command-mismatch",
                "login-ready-mismatch")) {
            try (BrowserWorkerClient client = loginClient("login-failure-" + mode, mode)) {
                assertThrows(
                        BrowserWorkerException.class,
                        () -> client.beginLogin(
                                loginTask(
                                        java.util.UUID.randomUUID().toString(),
                                        site(1, 1, SiteContracts.SiteCredential.none())),
                                new byte[0],
                                network(),
                                new CancellationSource()));
            }
        }

        assertLoginSaveFails("login-empty-state");
        assertLoginSaveFails("login-saved-mismatch");
    }

    @Test
    void oauthIsIdempotentOnlyForFrozenEndpointAndRevocationFailsClosed() {
        String sessionId = java.util.UUID.randomUUID().toString();
        McpOAuthBrowserTask task = oauthTask(sessionId, 1);
        BrowserWorkerClient client = oauthClient("oauth-revoke", "oauth-wait");
        McpOAuthBrowserSession pending = client.beginOAuth(task, network(), ignored -> {}, new CancellationSource());
        assertEquals(McpOAuthBrowserState.PENDING, pending.state());
        assertEquals(pending, client.beginOAuth(task, network(), ignored -> {}, new CancellationSource()));
        assertThrows(
                IllegalArgumentException.class,
                () -> client.beginOAuth(oauthTask(sessionId, 2), network(), ignored -> {}, new CancellationSource()));

        client.invalidateOAuth("endpoint-test", 2);

        McpOAuthBrowserSession failed = client.oauthStatus(sessionId);
        assertEquals(McpOAuthBrowserState.FAILED, failed.state());
        assertEquals("OAUTH_ENDPOINT_REVOKED", failed.failureCode().orElseThrow());
        assertEquals(McpOAuthBrowserState.FAILED, client.cancelOAuth(sessionId).state());
        assertThrows(
                IllegalArgumentException.class,
                () -> client.oauthStatus(java.util.UUID.randomUUID().toString()));
        assertThrows(IllegalArgumentException.class, () -> client.oauthStatus("not-a-uuid"));
        client.close();
        client.close();
        assertThrows(
                IllegalStateException.class,
                () -> client.beginOAuth(
                        oauthTask(java.util.UUID.randomUUID().toString(), 1),
                        network(),
                        ignored -> {},
                        new CancellationSource()));
    }

    @Test
    void oauthCapsConcurrentWindowsAndRejectsMalformedWorkerResults() {
        try (BrowserWorkerClient client = oauthClient("oauth-limit", "oauth-wait")) {
            for (int index = 0; index < 4; index++) {
                client.beginOAuth(
                        oauthTask(java.util.UUID.randomUUID().toString(), 1),
                        network(),
                        ignored -> {},
                        new CancellationSource());
            }
            assertThrows(
                    IllegalStateException.class,
                    () -> client.beginOAuth(
                            oauthTask(java.util.UUID.randomUUID().toString(), 1),
                            network(),
                            ignored -> {},
                            new CancellationSource()));
        }

        for (String mode : List.of(
                "exit",
                "oauth-error-before-ready",
                "oauth-network-sequence",
                "oauth-command-mismatch",
                "oauth-ready-mismatch")) {
            try (BrowserWorkerClient client = oauthClient("oauth-failure-" + mode, mode)) {
                assertThrows(
                        RuntimeException.class,
                        () -> client.beginOAuth(
                                oauthTask(java.util.UUID.randomUUID().toString(), 1),
                                network(),
                                ignored -> {},
                                new CancellationSource()));
            }
        }

        assertOAuthEventuallyFails("oauth-sensitive-result");
        assertOAuthEventuallyFails("oauth-callback-mismatch");
    }

    @Test
    void oauthCallbackCannotCommitAfterRealtimeRevocation() throws Exception {
        CountDownLatch callbackEntered = new CountDownLatch(1);
        CountDownLatch releaseCallback = new CountDownLatch(1);
        String sessionId = java.util.UUID.randomUUID().toString();
        try (BrowserWorkerClient client = oauthClient("oauth-callback-revoke", null)) {
            client.beginOAuth(
                    oauthTask(sessionId, 1),
                    network(),
                    ignored -> {
                        callbackEntered.countDown();
                        assertTrue(releaseCallback.await(2, TimeUnit.SECONDS));
                    },
                    new CancellationSource());
            assertTrue(callbackEntered.await(2, TimeUnit.SECONDS));

            client.invalidateOAuth("endpoint-test", 2);
            releaseCallback.countDown();

            McpOAuthBrowserSession terminal = awaitOAuthTerminal(client, sessionId);
            assertEquals(McpOAuthBrowserState.FAILED, terminal.state());
            assertEquals("OAUTH_ENDPOINT_REVOKED", terminal.failureCode().orElseThrow());
        }
    }

    @Test
    void unavailableCapabilitiesAndClosedClientFailBeforeLaunchingWorker() {
        BrowserWorkerClient client = new BrowserWorkerClient(command(null, null), Duration.ofSeconds(1));
        assertThrows(
                UnsupportedOperationException.class,
                () -> client.beginLogin(
                        loginTask(
                                java.util.UUID.randomUUID().toString(),
                                site(1, 1, SiteContracts.SiteCredential.none())),
                        new byte[0],
                        network(),
                        new CancellationSource()));
        assertThrows(
                UnsupportedOperationException.class,
                () -> client.beginOAuth(
                        oauthTask(java.util.UUID.randomUUID().toString(), 1),
                        network(),
                        ignored -> {},
                        new CancellationSource()));
        client.close();
        assertThrows(
                IllegalStateException.class,
                () -> client.snapshot(
                        snapshotTask(site(1, 1, SiteContracts.SiteCredential.none())),
                        new byte[0],
                        network(),
                        new CancellationSource()));
        assertThrows(IllegalArgumentException.class, () -> client.invalidate("", 0));
        assertThrows(IllegalArgumentException.class, () -> client.invalidate("docs", -1));
        assertThrows(IllegalArgumentException.class, () -> new BrowserWorkerClient(command(null, null), Duration.ZERO));
        assertThrows(
                IllegalArgumentException.class,
                () -> new BrowserWorkerClient(command(null, null), Duration.ofMinutes(11)));
    }

    private void assertLoginSaveFails(String mode) {
        String sessionId = java.util.UUID.randomUUID().toString();
        try (BrowserWorkerClient client = loginClient("save-failure-" + mode, mode)) {
            client.beginLogin(
                    loginTask(sessionId, site(1, 1, SiteContracts.SiteCredential.none())),
                    new byte[0],
                    network(),
                    new CancellationSource());
            assertThrows(BrowserWorkerException.class, () -> client.saveLogin(sessionId, ignored -> null));
            assertEquals(
                    SiteContracts.LoginSessionState.FAILED,
                    client.loginStatus(sessionId).state());
        }
    }

    private void assertOAuthEventuallyFails(String mode) {
        String sessionId = java.util.UUID.randomUUID().toString();
        try (BrowserWorkerClient client = oauthClient("result-failure-" + mode, mode)) {
            client.beginOAuth(oauthTask(sessionId, 1), network(), ignored -> {}, new CancellationSource());
            assertEquals(
                    McpOAuthBrowserState.FAILED,
                    awaitOAuthTerminal(client, sessionId).state());
        }
    }

    private static McpOAuthBrowserSession awaitOAuthTerminal(BrowserWorkerClient client, String sessionId) {
        long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
        McpOAuthBrowserSession state = client.oauthStatus(sessionId);
        while (!Set.of(
                        McpOAuthBrowserState.COMPLETED,
                        McpOAuthBrowserState.CANCELLED,
                        McpOAuthBrowserState.EXPIRED,
                        McpOAuthBrowserState.FAILED)
                .contains(state.state())) {
            if (System.nanoTime() >= deadline) {
                throw new AssertionError("OAuth Browser did not reach a terminal state");
            }
            Thread.onSpinWait();
            state = client.oauthStatus(sessionId);
        }
        return state;
    }

    private BrowserWorkerClient loginClient(String directory, String mode) {
        Path root = temporaryDirectory.resolve(directory);
        return new BrowserWorkerClient(
                command(mode, root), Duration.ofSeconds(1), root, new BrowserWorkerCapabilities(true, false));
    }

    private BrowserWorkerClient oauthClient(String directory, String mode) {
        Path root = temporaryDirectory.resolve(directory);
        return new BrowserWorkerClient(
                command(mode, root), Duration.ofSeconds(1), root, new BrowserWorkerCapabilities(false, true));
    }

    private BrowserWorkerClient.WorkerLauncher command(String mode, Path controlRoot) {
        Path java = Path.of(System.getProperty("java.home"), "bin", executableName());
        ArrayList<String> command = new ArrayList<>(List.of(
                java.toString(),
                "-cp",
                System.getProperty("java.class.path"),
                "com.javaclaw.browser.testing.FakeBrowserWorkerMain"));
        if (mode != null) {
            command.add(mode);
        }
        List<String> argv = List.copyOf(command);
        return () -> {
            ProcessBuilder builder = new ProcessBuilder(argv).redirectError(ProcessBuilder.Redirect.DISCARD);
            if (controlRoot != null) {
                builder.environment().put("JAVACLAW_BROWSER_CONTROL_ROOT", controlRoot.toString());
            }
            return builder.start();
        };
    }

    private static BrowserNetworkExchange network() {
        return (request, body, cancellation) ->
                new BrowserNetworkResult(new BrowserWorkerProtocol.NetworkResponse(204, Map.of(), false), new byte[0]);
    }

    private static SiteContracts.LoginBeginTask loginTask(String sessionId, SiteContracts.Site site) {
        return new SiteContracts.LoginBeginTask(site, sessionId, Duration.ofSeconds(3));
    }

    private static SiteContracts.SnapshotTask snapshotTask(SiteContracts.Site site) {
        return new SiteContracts.SnapshotTask(
                site, URI.create("https://docs.example.com/start"), 1_000, Duration.ofSeconds(2));
    }

    private static SiteContracts.Site site(
            long revision, long authorityRevision, SiteContracts.SiteCredential credential) {
        URI origin = URI.create("https://docs.example.com");
        return new SiteContracts.Site(
                "docs",
                revision,
                authorityRevision,
                "Docs",
                origin,
                Set.of(origin),
                credential,
                Optional.empty(),
                true,
                Instant.EPOCH);
    }

    private static McpOAuthBrowserTask oauthTask(String sessionId, long revision) {
        return new McpOAuthBrowserTask(
                sessionId,
                "authorization-test",
                "endpoint-test",
                revision,
                URI.create("https://auth.example/authorize?state=private-state"),
                Set.of(URI.create("https://auth.example")),
                URI.create("http://127.0.0.1:17845/oauth/callback"),
                Duration.ofSeconds(3));
    }

    private static String executableName() {
        return System.getProperty("os.name", "")
                        .toLowerCase(java.util.Locale.ROOT)
                        .contains("win")
                ? "java.exe"
                : "java";
    }
}
