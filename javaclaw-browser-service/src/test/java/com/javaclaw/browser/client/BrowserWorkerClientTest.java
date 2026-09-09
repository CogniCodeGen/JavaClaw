package com.javaclaw.browser.client;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.javaclaw.api.CancellationSource;
import com.javaclaw.api.CredentialRef;
import com.javaclaw.browser.protocol.BrowserWorkerProtocol;
import com.javaclaw.builtin.contracts.SiteContracts;
import com.javaclaw.protocol.CanonicalJson;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BrowserWorkerClientTest {
    private final CanonicalJson json = new CanonicalJson();

    @TempDir
    Path temporaryDirectory;

    @Test
    void oneShotSandboxProcessCanRestartAndUsesHostNetworkCallback() {
        AtomicInteger networkCalls = new AtomicInteger();
        try (BrowserWorkerClient client = new BrowserWorkerClient(command(), Duration.ofSeconds(5))) {
            SiteContracts.PageSnapshot first = invoke(client, "first", networkCalls);
            SiteContracts.PageSnapshot second = invoke(client, "second", networkCalls);

            assertEquals("https://docs.example.com/first", first.uri().toASCIIString());
            assertEquals("worker-process", first.text());
            assertEquals("https://docs.example.com/second", second.uri().toASCIIString());
            assertEquals(2, networkCalls.get());
        }
    }

    @Test
    void rejectsWorkerErrorAndMismatchedResponseId() {
        try (BrowserWorkerClient rejected = new BrowserWorkerClient(command("error"), Duration.ofSeconds(2));
                BrowserWorkerClient mismatched = new BrowserWorkerClient(command("mismatch"), Duration.ofSeconds(2))) {
            assertThrows(BrowserWorkerException.class, () -> invoke(rejected, "rejected", new AtomicInteger()));
            assertThrows(BrowserWorkerException.class, () -> invoke(mismatched, "mismatch", new AtomicInteger()));
        }
    }

    @Test
    void wrapsWorkerTransportFailure() {
        try (BrowserWorkerClient client = new BrowserWorkerClient(command("exit"), Duration.ofSeconds(2))) {
            assertThrows(BrowserWorkerException.class, () -> invoke(client, "exit", new AtomicInteger()));
        }
    }

    @Test
    void 启动期间客户端关闭仍终止尚未登记的Worker() {
        AtomicReference<BrowserWorkerClient> owner = new AtomicReference<>();
        AtomicReference<Process> started = new AtomicReference<>();
        BrowserWorkerClient.WorkerLauncher delegate = command();
        try (BrowserWorkerClient client = new BrowserWorkerClient(
                () -> {
                    Process process = delegate.start();
                    started.set(process);
                    owner.get().close();
                    return process;
                },
                Duration.ofSeconds(3))) {
            owner.set(client);

            assertThrows(
                    BrowserWorkerException.class, () -> invoke(client, "closed-during-start", new AtomicInteger()));
            assertFalse(started.get().isAlive());
        } finally {
            if (started.get() != null) {
                started.get().destroyForcibly();
            }
        }
    }

    @Test
    void validatesSensitiveStateAgainstCredentialKind() {
        try (BrowserWorkerClient client = new BrowserWorkerClient(command(), Duration.ofSeconds(2))) {
            SiteContracts.SnapshotTask noCredential = task("plain", SiteContracts.SiteCredential.none());
            assertThrows(
                    IllegalArgumentException.class,
                    () -> client.snapshot(
                            noCredential, new byte[] {1}, network(new AtomicInteger()), new CancellationSource()));

            SiteContracts.SiteCredential storage = new SiteContracts.SiteCredential(
                    SiteContracts.CredentialKind.BROWSER_STORAGE,
                    Optional.of(new CredentialRef(SiteContracts.BROWSER_CREDENTIAL_NAMESPACE, "state")),
                    Optional.empty());
            assertThrows(
                    IllegalArgumentException.class,
                    () -> client.snapshot(
                            task("storage", storage),
                            new byte[0],
                            network(new AtomicInteger()),
                            new CancellationSource()));
            assertThrows(
                    NullPointerException.class,
                    () -> client.snapshot(null, new byte[0], network(new AtomicInteger()), new CancellationSource()));
        }
    }

    @Test
    void interactiveLoginUsesPrivateResultFrameAndHostControlFile() {
        AtomicInteger networkCalls = new AtomicInteger();
        SiteContracts.SnapshotTask snapshotTask = task("login", SiteContracts.SiteCredential.none());
        String sessionId = java.util.UUID.randomUUID().toString();
        SiteContracts.LoginBeginTask loginTask =
                new SiteContracts.LoginBeginTask(snapshotTask.site(), sessionId, Duration.ofSeconds(3));
        try (BrowserWorkerClient client = new BrowserWorkerClient(
                command(null, temporaryDirectory),
                Duration.ofSeconds(2),
                temporaryDirectory,
                new BrowserWorkerCapabilities(true, false))) {
            assertTrue(client.interactiveLoginAvailable());
            assertFalse(client.oauthAvailable());
            SiteContracts.LoginSession ready =
                    client.beginLogin(loginTask, new byte[0], network(networkCalls), new CancellationSource());

            assertEquals(SiteContracts.LoginSessionState.READY, ready.state());
            int bytes = client.saveLogin(sessionId, state -> {
                assertTrue(new String(state, java.nio.charset.StandardCharsets.UTF_8).contains("browser-login-marker"));
                return state.length;
            });

            assertTrue(bytes > 0);
            assertEquals(
                    SiteContracts.LoginSessionState.SAVED,
                    client.loginStatus(sessionId).state());
            assertEquals(1, networkCalls.get());
        }
    }

    @Test
    void oauthCallbackStaysOnPrivateWorkerCallbackAndNeverUsesLoopbackNetwork() throws Exception {
        AtomicInteger networkCalls = new AtomicInteger();
        AtomicReference<URI> callback = new AtomicReference<>();
        String sessionId = java.util.UUID.randomUUID().toString();
        URI authorization = URI.create(
                "https://auth.example/authorize?client_id=test&state=private-state&code_challenge=private-challenge");
        McpOAuthBrowserTask task = new McpOAuthBrowserTask(
                sessionId,
                "oauth-test",
                "endpoint-test",
                3,
                authorization,
                Set.of(URI.create("https://auth.example")),
                URI.create("http://127.0.0.1:17845/oauth/callback"),
                Duration.ofSeconds(3));
        try (BrowserWorkerClient client = new BrowserWorkerClient(
                command(null, temporaryDirectory),
                Duration.ofSeconds(2),
                temporaryDirectory,
                new BrowserWorkerCapabilities(false, true))) {
            assertFalse(client.interactiveLoginAvailable());
            assertTrue(client.oauthAvailable());
            McpOAuthBrowserSession pending =
                    client.beginOAuth(task, network(networkCalls), callback::set, new CancellationSource());
            assertTrue(pending.state() == McpOAuthBrowserState.PENDING
                    || pending.state() == McpOAuthBrowserState.COMPLETED);
            long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
            while (client.oauthStatus(sessionId).state() != McpOAuthBrowserState.COMPLETED
                    && System.nanoTime() < deadline) {
                Thread.sleep(10);
            }

            assertEquals(
                    McpOAuthBrowserState.COMPLETED,
                    client.oauthStatus(sessionId).state());
            assertEquals(
                    "http://127.0.0.1:17845/oauth/callback?code=fake-code&state=private-state",
                    callback.get().toASCIIString());
            assertEquals(1, networkCalls.get());
        }
    }

    @Test
    void oauthCancelUsesPrivateControlFileAndReturnsNoCallback() {
        String sessionId = java.util.UUID.randomUUID().toString();
        McpOAuthBrowserTask task = new McpOAuthBrowserTask(
                sessionId,
                "oauth-cancel",
                "endpoint-test",
                1,
                URI.create("https://auth.example/authorize?state=private-state"),
                Set.of(URI.create("https://auth.example")),
                URI.create("http://127.0.0.1:17845/oauth/callback"),
                Duration.ofSeconds(3));
        try (BrowserWorkerClient client = new BrowserWorkerClient(
                command("oauth-wait", temporaryDirectory),
                Duration.ofSeconds(2),
                temporaryDirectory,
                new BrowserWorkerCapabilities(false, true))) {
            client.beginOAuth(
                    task,
                    network(new AtomicInteger()),
                    ignored -> {
                        throw new AssertionError("cancelled OAuth must not deliver a callback");
                    },
                    new CancellationSource());

            McpOAuthBrowserSession cancelled = client.cancelOAuth(sessionId);

            assertEquals(McpOAuthBrowserState.CANCELLED, cancelled.state());
            assertTrue(cancelled.failureCode().isEmpty());
        }
    }

    private SiteContracts.PageSnapshot invoke(BrowserWorkerClient client, String path, AtomicInteger networkCalls) {
        CanonicalPayloadResult result = new CanonicalPayloadResult(client.snapshot(
                task(path, SiteContracts.SiteCredential.none()),
                new byte[0],
                network(networkCalls),
                new CancellationSource()));
        return json.decode(result.payload(), SiteContracts.PageSnapshot.class);
    }

    private static BrowserNetworkExchange network(AtomicInteger calls) {
        return (request, body, cancellation) -> {
            calls.incrementAndGet();
            assertEquals("GET", request.method());
            assertEquals(0, body.length);
            return new BrowserNetworkResult(
                    new BrowserWorkerProtocol.NetworkResponse(204, Map.of("x-host", List.of("test")), false),
                    new byte[0]);
        };
    }

    private static SiteContracts.SnapshotTask task(String path, SiteContracts.SiteCredential credential) {
        URI origin = URI.create("https://docs.example.com");
        SiteContracts.Site site = new SiteContracts.Site(
                "docs", 1, 1, "Docs", origin, Set.of(origin), credential, Optional.empty(), true, Instant.EPOCH);
        return new SiteContracts.SnapshotTask(site, URI.create(origin + "/" + path), 1_000, Duration.ofSeconds(2));
    }

    private BrowserWorkerClient.WorkerLauncher command() {
        return command(null);
    }

    private BrowserWorkerClient.WorkerLauncher command(String mode) {
        return command(mode, null);
    }

    private BrowserWorkerClient.WorkerLauncher command(String mode, Path controlRoot) {
        Path java = Path.of(System.getProperty("java.home"), "bin", executableName());
        java.util.ArrayList<String> command = new java.util.ArrayList<>(List.of(
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

    private static String executableName() {
        return System.getProperty("os.name", "")
                        .toLowerCase(java.util.Locale.ROOT)
                        .contains("win")
                ? "java.exe"
                : "java";
    }

    private record CanonicalPayloadResult(com.javaclaw.api.CanonicalPayload payload) {}
}
