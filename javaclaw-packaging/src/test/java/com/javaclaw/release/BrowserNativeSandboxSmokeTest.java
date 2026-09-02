package com.javaclaw.release;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.javaclaw.api.CancellationSource;
import com.javaclaw.api.ResourceLimits;
import com.javaclaw.browser.client.BrowserNetworkResult;
import com.javaclaw.browser.client.BrowserWorkerCapabilities;
import com.javaclaw.browser.client.BrowserWorkerClient;
import com.javaclaw.browser.client.McpOAuthBrowserSession;
import com.javaclaw.browser.client.McpOAuthBrowserState;
import com.javaclaw.browser.client.McpOAuthBrowserTask;
import com.javaclaw.browser.protocol.BrowserWorkerProtocol;
import com.javaclaw.builtin.contracts.SiteContracts;
import com.javaclaw.nativehost.sandbox.SandboxedWorkerCommand;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BrowserNativeSandboxSmokeTest {
    private static final URI ORIGIN = URI.create("https://broker-only.invalid");
    private static final String SESSION_ID = "4ed41f28-3036-4b7e-ad61-3415b4a86762";
    private static final String OAUTH_SESSION_ID = "d4e3a817-8bc3-44cf-ae65-c291786615b0";
    private static final String OAUTH_STATE = "runner-private-state";
    private static final URI OAUTH_REDIRECT = URI.create("http://127.0.0.1:17845/oauth/callback");

    @TempDir
    Path temporaryDirectory;

    @Test
    void 五平台Runner只在原生Sandbox登录和OAuth私有回调成功后分别发布能力() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("javaclaw.require.native.sandbox"));
        Path image = distributionRoot().resolve("workers/browser").toRealPath();
        BrowserNativeCapability.INTERACTIVE_LOGIN.prepare(image);
        BrowserNativeCapability.MCP_OAUTH.prepare(image);
        requireBrowserImage(image);
        Path work = Files.createDirectories(temporaryDirectory.resolve("work")).toRealPath();
        Path control =
                Files.createDirectories(temporaryDirectory.resolve("control")).toRealPath();
        AtomicInteger brokerCalls = new AtomicInteger();
        AtomicReference<URI> oauthCallback = new AtomicReference<>();

        try (BrowserWorkerClient browser = new BrowserWorkerClient(
                command(image, work, control),
                Duration.ofSeconds(45),
                control,
                new BrowserWorkerCapabilities(true, true))) {
            SiteContracts.LoginSession ready = browser.beginLogin(
                    loginTask(),
                    new byte[0],
                    (request, body, cancellation) -> loginBrokerResponse(request, brokerCalls),
                    new CancellationSource());
            String digest = browser.saveLogin(ready.sessionId(), BrowserNativeSandboxSmokeTest::digest);

            assertEquals(SiteContracts.LoginSessionState.READY, ready.state());
            assertEquals(
                    SiteContracts.LoginSessionState.SAVED,
                    browser.loginStatus(ready.sessionId()).state());
            assertEquals(64, digest.length());
            assertTrue(brokerCalls.get() > 0, "Chromium 导航必须经宿主 Broker 回调完成");
            BrowserNativeCapability.INTERACTIVE_LOGIN.publish(image);

            McpOAuthBrowserSession oauth = browser.beginOAuth(
                    oauthTask(),
                    (request, body, cancellation) -> oauthBrokerResponse(request, brokerCalls),
                    oauthCallback::set,
                    new CancellationSource());
            awaitOAuth(browser, oauth.sessionId());
            assertEquals(
                    OAUTH_REDIRECT + "?code=runner-private-code&state=" + OAUTH_STATE,
                    oauthCallback.get().toASCIIString());
            BrowserNativeCapability.MCP_OAUTH.publish(image);
        }

        assertReceipt(image, BrowserNativeCapability.INTERACTIVE_LOGIN);
        assertReceipt(image, BrowserNativeCapability.MCP_OAUTH);
    }

    private static SandboxedWorkerCommand command(Path image, Path work, Path control) throws Exception {
        String executableSuffix = BrowserNativeCapability.platformId().equals("windows") ? ".exe" : "";
        Path java = image.resolve("bin/java" + executableSuffix).toRealPath();
        Path app = image.resolve("app").toRealPath();
        Path browser = image.resolve("browser").toRealPath();
        DisplayAccess display = displayAccess();
        LinkedHashMap<String, String> environment = new LinkedHashMap<>();
        environment.put("JAVACLAW_BROWSER_CONTROL_ROOT", control.toString());
        environment.put("PLAYWRIGHT_BROWSERS_PATH", browser.toString());
        environment.put("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD", "1");
        environment.put("TMPDIR", work.toString());
        environment.putAll(display.environment());
        ArrayList<Path> readRoots = new ArrayList<>(List.of(image, control));
        readRoots.addAll(display.readRoots());
        List<String> argv = List.of(
                java.toString(),
                "-XX:-UsePerfData",
                "-cp",
                app.resolve("*").toString(),
                "com.javaclaw.browser.worker.BrowserWorkerMain");
        return new SandboxedWorkerCommand(
                "browser-native-smoke",
                argv,
                work,
                environment,
                readRoots,
                List.of(work),
                List.of(java, browser),
                Duration.ofMinutes(2),
                new ResourceLimits(1024L * 1024 * 1024, 16L * 1024 * 1024, 16, 512));
    }

    private static DisplayAccess displayAccess() throws Exception {
        if (!BrowserNativeCapability.platformId().equals("linux")) {
            return new DisplayAccess(Map.of(), List.of());
        }
        String display = System.getenv().getOrDefault("DISPLAY", "").strip();
        if (!display.matches(":[0-9]+(?:\\.[0-9]+)?")) {
            throw new IllegalStateException("Linux Browser native smoke requires a local Xvfb DISPLAY");
        }
        int separator = display.indexOf('.');
        String number = display.substring(1, separator < 0 ? display.length() : separator);
        Path socketRoot = Path.of("/tmp/.X11-unix").toRealPath();
        if (!Files.exists(socketRoot.resolve("X" + number))) {
            throw new IllegalStateException("Linux Xvfb socket does not match DISPLAY");
        }
        LinkedHashMap<String, String> environment = new LinkedHashMap<>();
        environment.put("DISPLAY", display);
        ArrayList<Path> readRoots = new ArrayList<>(List.of(socketRoot));
        String authority = System.getenv().getOrDefault("XAUTHORITY", "").strip();
        if (!authority.isEmpty()) {
            Path file = Path.of(authority).toRealPath();
            if (!Files.isRegularFile(file)) {
                throw new IllegalStateException("XAUTHORITY is not a regular file");
            }
            environment.put("XAUTHORITY", file.toString());
            readRoots.add(file);
        }
        return new DisplayAccess(environment, readRoots);
    }

    private static SiteContracts.LoginBeginTask loginTask() {
        SiteContracts.Site site = new SiteContracts.Site(
                "native-smoke",
                1,
                1,
                "Native smoke",
                ORIGIN,
                Set.of(ORIGIN),
                SiteContracts.SiteCredential.none(),
                Optional.empty(),
                true,
                Instant.parse("2026-09-01T00:00:00Z"));
        return new SiteContracts.LoginBeginTask(site, SESSION_ID, Duration.ofMinutes(1));
    }

    private static McpOAuthBrowserTask oauthTask() {
        URI authorization = URI.create(ORIGIN
                + "/authorize?response_type=code&state="
                + OAUTH_STATE
                + "&code_challenge=runner-private-challenge&code_challenge_method=S256");
        return new McpOAuthBrowserTask(
                OAUTH_SESSION_ID,
                "runner-oauth",
                "runner-endpoint",
                1,
                authorization,
                Set.of(ORIGIN),
                OAUTH_REDIRECT,
                Duration.ofMinutes(1));
    }

    private static BrowserNetworkResult loginBrokerResponse(
            BrowserWorkerProtocol.NetworkRequest request, AtomicInteger brokerCalls) {
        requireExactOrigin(request);
        brokerCalls.incrementAndGet();
        byte[] html = "<html><body><input type=password value=masked></body></html>".getBytes(StandardCharsets.UTF_8);
        BrowserWorkerProtocol.NetworkResponse response = new BrowserWorkerProtocol.NetworkResponse(
                200, Map.of("content-type", List.of("text/html; charset=utf-8")), false);
        return new BrowserNetworkResult(response, html);
    }

    private static BrowserNetworkResult oauthBrokerResponse(
            BrowserWorkerProtocol.NetworkRequest request, AtomicInteger brokerCalls) {
        requireExactOrigin(request);
        brokerCalls.incrementAndGet();
        String callback = OAUTH_REDIRECT + "?code=runner-private-code&state=" + OAUTH_STATE;
        BrowserWorkerProtocol.NetworkResponse response =
                new BrowserWorkerProtocol.NetworkResponse(302, Map.of("location", List.of(callback)), false);
        return new BrowserNetworkResult(response, new byte[0]);
    }

    private static void requireExactOrigin(BrowserWorkerProtocol.NetworkRequest request) {
        if (!ORIGIN.equals(SiteContracts.originOf(request.uri()))) {
            throw new SecurityException("native smoke Browser escaped its exact Origin");
        }
    }

    private static void awaitOAuth(BrowserWorkerClient browser, String sessionId) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (browser.oauthStatus(sessionId).state() != McpOAuthBrowserState.COMPLETED
                && System.nanoTime() < deadline) {
            Thread.sleep(50);
        }
        assertEquals(
                McpOAuthBrowserState.COMPLETED, browser.oauthStatus(sessionId).state());
    }

    private static void assertReceipt(Path image, BrowserNativeCapability capability) throws Exception {
        Path marker = image.resolve(capability.fileName());
        assertEquals(
                capability.receipt(),
                Files.readString(marker, StandardCharsets.US_ASCII).strip());
        assertTrue(BrowserNativeCapability.isReadOnly(marker));
    }

    private static String digest(byte[] storageState) throws Exception {
        assertTrue(storageState.length > 0);
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(storageState));
    }

    private static void requireBrowserImage(Path image) throws Exception {
        Path marker = image.resolve(WorkerImageAssemblerMain.IMAGE_MARKER);
        if (!Files.isRegularFile(marker)
                || !"worker-image-v1:browser"
                        .equals(Files.readString(marker, StandardCharsets.US_ASCII)
                                .strip())) {
            throw new IllegalStateException(
                    "native Browser smoke requires -Djavaclaw.playwright.browsers.path with Playwright 1.52 Chromium");
        }
    }

    private static Path distributionRoot() {
        Path root = Path.of(System.getProperty("maven.multiModuleProjectDirectory", ""))
                .toAbsolutePath()
                .normalize();
        Path repository = Files.isDirectory(root.resolve("javaclaw-packaging")) ? root : root.getParent();
        return repository.resolve("javaclaw-packaging/target/distribution");
    }

    private record DisplayAccess(Map<String, String> environment, List<Path> readRoots) {
        private DisplayAccess {
            environment = Map.copyOf(environment);
            readRoots = List.copyOf(readRoots);
        }
    }
}
