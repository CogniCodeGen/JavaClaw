package com.javaclaw.release;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.javaclaw.api.CancellationSource;
import com.javaclaw.api.ThreadId;
import com.javaclaw.api.WorkspaceId;
import com.javaclaw.browser.client.BrowserActionResult;
import com.javaclaw.browser.client.BrowserNetworkResult;
import com.javaclaw.browser.client.BrowserWorkerCapabilities;
import com.javaclaw.browser.client.BrowserWorkerClient;
import com.javaclaw.browser.protocol.BrowserWorkerProtocol;
import com.javaclaw.builtin.contracts.BrowserContracts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 可见 Chromium 的本地固定页面门禁；不使用真实账户、外网、凭据或模型调用。 */
class BrowserInteractiveNativeSmokeTest {
    private static final URI ORIGIN = URI.create("https://interactive-fixture.invalid");
    private static final byte[] PAGE = ("<html><head><title>Native interactive fixture</title></head><body>"
                    + "<label>Name<input id=name aria-label=Name></label>"
                    + "<button onclick=\"document.getElementById('result').textContent=document.getElementById('name').value\">Apply</button>"
                    + "<p id=result>empty</p></body></html>")
            .getBytes(StandardCharsets.UTF_8);

    @TempDir
    Path temporaryDirectory;

    @Test
    void 仅真实可见交互和跨租约页面保留成功后发布独立能力() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("javaclaw.require.native.sandbox"));
        Path image = BrowserNativeSandboxSmokeTest.distributionRoot()
                .resolve("workers/browser")
                .toRealPath();
        BrowserNativeCapability capability = BrowserNativeCapability.INTERACTIVE_BROWSER;
        capability.prepare(image);
        com.javaclaw.nativehost.sandbox.SandboxedWorkerLauncher.verifyInteractiveIsolation();
        com.javaclaw.nativehost.sandbox.SandboxedWorkerLauncher.verifyInteractiveProcessContainment();
        BrowserNativeSandboxSmokeTest.requireBrowserImage(image);
        Path work = Files.createDirectory(temporaryDirectory.resolve("work")).toRealPath();
        Path control =
                Files.createDirectory(temporaryDirectory.resolve("control")).toRealPath();
        AtomicInteger brokerCalls = new AtomicInteger();
        BrowserContracts.OpenTask task = task();
        try (BrowserWorkerClient browser = new BrowserWorkerClient(
                BrowserNativeSandboxSmokeTest.command(image, work, control),
                Duration.ofSeconds(45),
                control,
                new BrowserWorkerCapabilities(false, false, true))) {
            verifyInteractions(browser, task, brokerCalls);
            browser.closeInteractive(task.sessionId());
        }
        assertTrue(brokerCalls.get() > 0, "可见页面的所有导航必须通过宿主固定 Broker");
        capability.publish(image);
        assertEquals(
                capability.receipt(),
                Files.readString(image.resolve(capability.fileName())).strip());
        assertTrue(BrowserNativeCapability.isReadOnly(image.resolve(capability.fileName())));
    }

    private static void verifyInteractions(
            BrowserWorkerClient browser, BrowserContracts.OpenTask task, AtomicInteger calls) throws Exception {
        CancellationSource cancellation = new CancellationSource();
        BrowserContracts.Observation opened;
        try (BrowserActionResult result = browser.openInteractive(
                task, new byte[0], (request, body, token) -> response(request, calls), cancellation)) {
            opened = result.observation();
        }
        assertEquals(BrowserContracts.SessionState.OPEN, opened.session().state());
        assertEquals("Native interactive fixture", opened.page().title());
        BrowserContracts.Observation filled =
                action(browser, task.sessionId(), opened, "Name", BrowserContracts.Operation.FILL, "native-retained");
        BrowserContracts.Observation clicked =
                action(browser, task.sessionId(), filled, "Apply", BrowserContracts.Operation.CLICK, "");
        assertTrue(clicked.page().text().contains("native-retained"));
        verifyScreenshot(browser, task.sessionId());
        browser.updateInteractiveLease(task.sessionId(), lease(2), cancellation);
        try (BrowserActionResult retained = browser.actInteractive(
                task.sessionId(),
                BrowserContracts.Action.simple(BrowserContracts.Operation.SNAPSHOT),
                new byte[0],
                cancellation)) {
            assertEquals(opened.page().pageId(), retained.observation().page().pageId());
            assertTrue(retained.observation().page().text().contains("native-retained"));
        }
    }

    private static BrowserContracts.Observation action(
            BrowserWorkerClient browser,
            String sessionId,
            BrowserContracts.Observation observed,
            String name,
            BrowserContracts.Operation operation,
            String value)
            throws Exception {
        BrowserContracts.Element element = observed.page().elements().stream()
                .filter(candidate -> candidate.name().equals(name))
                .findFirst()
                .orElseThrow();
        BrowserContracts.Action action = new BrowserContracts.Action(
                operation,
                new BrowserContracts.Target(observed.page().pageId(), element.reference(), ""),
                BrowserContracts.ActionInput.text(value));
        try (BrowserActionResult result =
                browser.actInteractive(sessionId, action, new byte[0], new CancellationSource())) {
            return result.observation();
        }
    }

    private static void verifyScreenshot(BrowserWorkerClient browser, String sessionId) throws Exception {
        try (BrowserActionResult screenshot = browser.actInteractive(
                sessionId,
                BrowserContracts.Action.simple(BrowserContracts.Operation.SCREENSHOT),
                new byte[0],
                new CancellationSource())) {
            assertTrue(screenshot.observation().frame().isPresent());
            assertTrue(screenshot.content().length > 8);
            assertEquals(
                    "image/png",
                    screenshot.observation().artifact().orElseThrow().file().mediaType());
            assertFalse(screenshot.observation().page().text().isBlank());
        }
    }

    private static BrowserContracts.OpenTask task() {
        BrowserContracts.Owner owner =
                new BrowserContracts.Owner(WorkspaceId.random(), ThreadId.random(), Optional.empty());
        return new BrowserContracts.OpenTask(UUID.randomUUID().toString(), owner, ORIGIN, lease(1));
    }

    private static BrowserContracts.AccessLease lease(long generation) {
        return new BrowserContracts.AccessLease(
                BrowserContracts.ControlMode.ASSISTANT,
                "native-turn-" + generation,
                generation,
                Instant.now().plusSeconds(60),
                Set.of(ORIGIN));
    }

    private static BrowserNetworkResult response(BrowserContracts.NetworkRequest request, AtomicInteger calls) {
        assertEquals(ORIGIN.getHost(), request.uri().getHost());
        assertEquals("https", request.uri().getScheme());
        calls.incrementAndGet();
        return new BrowserNetworkResult(
                new BrowserWorkerProtocol.NetworkResponse(
                        200, Map.of("content-type", List.of("text/html; charset=utf-8")), false),
                PAGE);
    }
}
