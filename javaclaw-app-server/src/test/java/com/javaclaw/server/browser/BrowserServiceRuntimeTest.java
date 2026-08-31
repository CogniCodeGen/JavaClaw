package com.javaclaw.server.browser;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.LinkedBlockingQueue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.javaclaw.sandbox.api.BrokerResponse;
import com.javaclaw.sandbox.api.NetworkPolicy;
import com.javaclaw.sandbox.api.SandboxCommand;
import com.javaclaw.sandbox.api.SandboxExecutor;
import com.javaclaw.sandbox.api.SandboxPolicy;
import com.javaclaw.sandbox.api.SandboxResult;
import com.javaclaw.sandbox.api.SandboxSession;
import com.javaclaw.sandbox.api.SandboxSessionFrame;
import com.javaclaw.sandbox.api.SandboxSessionOptions;
import com.javaclaw.sandbox.api.SandboxSignal;
import com.javaclaw.server.persistence.H2Persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BrowserServiceRuntimeTest {
    @TempDir
    Path temporary;

    @Test
    void fetchesThroughBrokerAndRendersInANetworkDisabledSandbox() throws Exception {
        ObjectMapper json = new ObjectMapper();
        FakeSandbox sandbox = new FakeSandbox(json);
        URI requested = URI.create("https://example.test/page");
        try (H2Persistence persistence = new H2Persistence(temporary.resolve("data"));
                BrowserServiceRuntime browser = new BrowserServiceRuntime(
                        (request, policy) -> {
                            assertEquals(NetworkPolicy.Mode.ALLOWLIST, policy.mode());
                            assertEquals(Set.of("example.test:443"), policy.allowedHosts());
                            return new BrokerResponse(
                                    200,
                                    requested,
                                    Map.of("content-type", List.of("text/html; charset=utf-8")),
                                    "<html><body>Hello</body></html>".getBytes(StandardCharsets.UTF_8),
                                    0);
                        },
                        persistence.attachments(),
                        sandbox,
                        new SandboxPolicy(
                                com.javaclaw.sandbox.api.SandboxMode.HOST_FULL_ACCESS,
                                Set.of(),
                                Set.of(),
                                Set.of(),
                                new NetworkPolicy(NetworkPolicy.Mode.FULL, Set.of()),
                                Set.of(),
                                Duration.ofMinutes(10),
                                64 * 1024 * 1024),
                        List.of("/test/browser-service"),
                        temporary.resolve("runtime"),
                        Set.of(temporary),
                        json)) {
            var result = browser.snapshot(requested, true, 2_000);
            assertEquals("Rendered", result.title());
            assertEquals("Hello", result.text());
            assertNotNull(result.screenshotAttachmentSha256());
            assertTrue(persistence
                    .attachments()
                    .findAttachment(result.screenshotAttachmentSha256())
                    .isPresent());
            assertEquals(
                    NetworkPolicy.Mode.DISABLED,
                    sandbox.command.policy().network().mode());
            Path runtime = temporary.resolve("runtime").toRealPath();
            assertTrue(sandbox.command.policy().writableRoots().stream().allMatch(path -> path.startsWith(runtime)));
        }
        try (var paths = Files.list(temporary.resolve("runtime"))) {
            assertEquals(0, paths.count(), "关闭 Browser 必须回收本次私有缓存");
        }
    }

    @Test
    void browserOriginsShareNetworkGrantNormalizationAndCleanupRejectsBroaderRoots() throws Exception {
        assertEquals(
                "[fd00:0:0:0:0:0:0:1]:8443",
                BrowserServiceRuntime.allowlistEntry(URI.create("https://[fd00::1]:8443/page")));
        assertThrows(java.io.IOException.class, () -> BrowserSessionFiles.remove(temporary, temporary));
        assertThrows(
                java.io.IOException.class,
                () -> BrowserSessionFiles.remove(temporary, temporary.resolve("ordinary-directory")));
    }

    private static final class FakeSandbox implements SandboxExecutor {
        private final ObjectMapper json;
        private SandboxCommand command;

        private FakeSandbox(ObjectMapper json) {
            this.json = json;
        }

        @Override
        public SandboxResult execute(SandboxCommand command) {
            throw new AssertionError("browser must use a persistent sandbox session");
        }

        @Override
        public SandboxSession openSession(SandboxCommand value, SandboxSessionOptions options) {
            command = value;
            assertEquals(SandboxSessionOptions.pipes(), options);
            return new FakeSession(json);
        }
    }

    private static final class FakeSession implements SandboxSession {
        private final ObjectMapper json;
        private final LinkedBlockingQueue<SandboxSessionFrame> output = new LinkedBlockingQueue<>();
        private volatile boolean alive = true;

        private FakeSession(ObjectMapper json) {
            this.json = json;
        }

        @Override
        public String id() {
            return "fake-browser";
        }

        @Override
        public SandboxSessionFrame read(Duration timeout) throws InterruptedException {
            return output.poll(timeout.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
        }

        @Override
        public void write(byte[] input) throws Exception {
            JsonNode request = json.readTree(input);
            ObjectNode response = json.createObjectNode();
            response.put("jsonrpc", "2.0");
            response.set("id", request.get("id"));
            ObjectNode result = response.putObject("result");
            if ("initialize".equals(request.path("method").asText())) {
                result.put("protocolVersion", 1);
                result.put("network", "disabled");
            } else {
                result.put("title", "Rendered");
                result.put("text", "Hello");
                result.put(
                        "screenshotBase64", Base64.getEncoder().encodeToString("png".getBytes(StandardCharsets.UTF_8)));
            }
            output.add(SandboxSessionFrame.stream(
                    "nonce",
                    SandboxSessionFrame.Kind.STDOUT,
                    (json.writeValueAsString(response) + "\n").getBytes(StandardCharsets.UTF_8)));
        }

        @Override
        public void closeInput() {}

        @Override
        public void resize(int columns, int rows) {}

        @Override
        public void signal(SandboxSignal signal) {}

        @Override
        public boolean isAlive() {
            return alive;
        }

        @Override
        public void terminate() {
            alive = false;
        }
    }
}
