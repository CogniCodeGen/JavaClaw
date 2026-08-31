package com.javaclaw.server.browser;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.javaclaw.agent.tool.LauncherProcessSandboxExecutor;
import com.javaclaw.sandbox.api.NetworkPolicy;
import com.javaclaw.sandbox.api.SandboxMode;
import com.javaclaw.sandbox.api.SandboxPolicy;
import com.javaclaw.server.persistence.H2Persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NativeBrowserInteractionTest {
    @TempDir
    Path temporary;

    @Test
    void realIsolatedChromiumUsesTheReverseBrokerAndRejectsStaleReferencesAndRawSecretFills() throws Exception {
        if (!Boolean.getBoolean("javaclaw.require.native.sandbox")) {
            return;
        }
        var json = new ObjectMapper();
        var modules = new ArrayList<Path>();
        for (String type : List.of(
                "com.javaclaw.core.api.ThreadId",
                "com.javaclaw.protocol.RpcMethods",
                "com.javaclaw.nativehost.sandbox.SandboxLauncherMain",
                "com.fasterxml.jackson.databind.ObjectMapper",
                "com.fasterxml.jackson.core.JsonFactory",
                "com.fasterxml.jackson.annotation.JsonProperty",
                "com.fasterxml.jackson.datatype.jsr310.JavaTimeModule",
                "com.fasterxml.jackson.datatype.jdk8.Jdk8Module")) {
            modules.add(location(Class.forName(type)));
        }
        String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        var roots = new LinkedHashSet<Path>();
        for (String entry : classpath.split(java.util.regex.Pattern.quote(java.io.File.pathSeparator))) {
            roots.add(Path.of(entry).toRealPath());
        }
        roots.add(Path.of(System.getProperty("java.home")).toRealPath());
        String os = System.getProperty("os.name").toLowerCase();
        Path browsers = System.getProperty("javaclaw.browser.assets") != null
                ? Path.of(System.getProperty("javaclaw.browser.assets"))
                : os.contains("mac")
                        ? Path.of(System.getProperty("user.home"), "Library", "Caches", "ms-playwright")
                        : os.contains("win")
                                ? Path.of(System.getenv("LOCALAPPDATA"), "ms-playwright")
                                : Path.of(System.getProperty("user.home"), ".cache", "ms-playwright");
        assertTrue(
                Files.isDirectory(browsers), "native Browser gate requires the packaged matching Playwright browsers");
        roots.add(browsers.toRealPath());
        var sandbox = new LauncherProcessSandboxExecutor(LauncherProcessSandboxExecutor.modularJavaCommand(modules));
        var diagnostics = new java.util.concurrent.CopyOnWriteArrayList<String>();
        com.javaclaw.sandbox.api.SandboxExecutor observed = new com.javaclaw.sandbox.api.SandboxExecutor() {
            @Override
            public com.javaclaw.sandbox.api.SandboxResult execute(com.javaclaw.sandbox.api.SandboxCommand command) {
                throw new AssertionError("browser must use a supervised session");
            }

            @Override
            public com.javaclaw.sandbox.api.SandboxSession openSession(
                    com.javaclaw.sandbox.api.SandboxCommand command,
                    com.javaclaw.sandbox.api.SandboxSessionOptions options)
                    throws Exception {
                assertEquals(
                        NetworkPolicy.Mode.DISABLED, command.policy().network().mode());
                var environment = new java.util.LinkedHashMap<>(command.environment());
                environment.put("DEBUG", "pw:browser");
                var inherited = new LinkedHashSet<>(command.policy().inheritedEnvironment());
                inherited.add("DEBUG");
                var policy = command.policy();
                var debugPolicy = new SandboxPolicy(
                        policy.mode(),
                        policy.readableRoots(),
                        policy.writableRoots(),
                        policy.protectedRoots(),
                        policy.network(),
                        inherited,
                        policy.timeout(),
                        policy.outputLimitBytes());
                var session = sandbox.openSession(
                        new com.javaclaw.sandbox.api.SandboxCommand(
                                command.id(),
                                command.argv(),
                                command.workingDirectory(),
                                environment,
                                debugPolicy,
                                command.standardInput(),
                                command.auxiliaryRole()),
                        options);
                return new com.javaclaw.sandbox.api.SandboxSession() {
                    @Override
                    public String id() {
                        return session.id();
                    }

                    @Override
                    public com.javaclaw.sandbox.api.SandboxSessionFrame read(Duration timeout) throws Exception {
                        var frame = session.read(timeout);
                        if (frame != null
                                && frame.kind() == com.javaclaw.sandbox.api.SandboxSessionFrame.Kind.STDERR
                                && diagnostics.size() < 128) {
                            diagnostics.add(new String(frame.data(), StandardCharsets.UTF_8));
                        }
                        return frame;
                    }

                    @Override
                    public void write(byte[] input) throws Exception {
                        session.write(input);
                    }

                    @Override
                    public void closeInput() throws Exception {
                        session.closeInput();
                    }

                    @Override
                    public void resize(int columns, int rows) throws Exception {
                        session.resize(columns, rows);
                    }

                    @Override
                    public void signal(com.javaclaw.sandbox.api.SandboxSignal signal) throws Exception {
                        session.signal(signal);
                    }

                    @Override
                    public boolean isAlive() {
                        return session.isAlive();
                    }

                    @Override
                    public void terminate() {
                        session.terminate();
                    }
                };
            }
        };
        var ceiling = new SandboxPolicy(
                SandboxMode.HOST_FULL_ACCESS,
                Set.of(),
                Set.of(),
                Set.of(),
                new NetworkPolicy(NetworkPolicy.Mode.FULL, Set.of()),
                Set.of(
                        "HOME",
                        "TMPDIR",
                        "LANG",
                        "DISPLAY",
                        "XAUTHORITY",
                        "WAYLAND_DISPLAY",
                        "XDG_RUNTIME_DIR",
                        "PLAYWRIGHT_BROWSERS_PATH",
                        "PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD"),
                Duration.ofMinutes(5),
                128 * 1024 * 1024);
        var command = List.of(
                Path.of(System.getProperty("java.home"), "bin", os.contains("win") ? "java.exe" : "java")
                        .toString(),
                "-cp",
                classpath,
                "com.javaclaw.browser.BrowserServiceMain");
        var methods = new CopyOnWriteArrayList<String>();
        var downloadRequests = new java.util.concurrent.atomic.AtomicInteger();
        var step = new java.util.concurrent.atomic.AtomicReference<>("open");
        try (var persistence = new H2Persistence(temporary.resolve("data"));
                var runtime = new BrowserServiceRuntime(
                        (request, policy) -> {
                            throw new AssertionError("static fetch not used");
                        },
                        persistence.attachments(),
                        observed,
                        ceiling,
                        command,
                        temporary.resolve("browser"),
                        roots,
                        json);
                var channel = runtime.openChannel(params -> {
                    String url = params.path("url").asText();
                    assertEquals("example.test", URI.create(url).getHost(), "every request must enter the broker");
                    methods.add(params.path("method").asText());
                    if (URI.create(url).getPath().equals("/download")) {
                        downloadRequests.incrementAndGet();
                        var download = json.createObjectNode()
                                .put("status", 200)
                                .put(
                                        "bodyBase64",
                                        Base64.getEncoder()
                                                .encodeToString("download fixture".getBytes(StandardCharsets.UTF_8)));
                        var headers = download.putObject("headers");
                        headers.putArray("content-type").add("application/octet-stream");
                        headers.putArray("content-disposition").add("attachment; filename=fixture.txt");
                        return download;
                    }
                    String html = URI.create(url).getPath().equals("/sent")
                            ? "<html><body>Submitted once</body></html>"
                            : """
                            <html><head><title>Browser fixture</title></head><body>
                            <form action="/sent" method="post"><input name="name" placeholder="Name">
                            <input name="password" type="password" placeholder="Password"><button>Send</button></form>
                            <input type="file" aria-label="Attachment">
                            <select aria-label="Choice"><option value="one">One</option><option value="two">Two</option></select>
                            <a href="/next">Next</a><a href="/download" download>Download</a></body></html>
                            """;
                    var result = json.createObjectNode()
                            .put("status", 200)
                            .put(
                                    "bodyBase64",
                                    Base64.getEncoder().encodeToString(html.getBytes(StandardCharsets.UTF_8)));
                    result.putObject("headers").putArray("content-type").add("text/html; charset=utf-8");
                    ((com.fasterxml.jackson.databind.node.ObjectNode) result.path("headers"))
                            .putArray("set-cookie")
                            .add("fixture=login-state; Path=/; Secure; HttpOnly; SameSite=Strict");
                    return result;
                })) {
            var page = channel.call(
                    "browser/open",
                    json.createObjectNode().put("url", "https://example.test/"),
                    Duration.ofSeconds(45));
            assertEquals("Browser fixture", page.path("title").asText());
            step.set("pdf");
            var pdf = channel.call(
                    "browser/action", json.createObjectNode().put("operation", "pdf"), Duration.ofSeconds(20));
            assertTrue(
                    new String(Base64.getDecoder().decode(pdf.path("bodyBase64").asText()), StandardCharsets.ISO_8859_1)
                            .startsWith("%PDF-"));
            step.set("upload");
            page = channel.call(
                    "browser/action",
                    json.createObjectNode()
                            .put("operation", "upload")
                            .put("reference", reference(page, "Attachment"))
                            .put("name", "fixture.txt")
                            .put("mediaType", "text/plain")
                            .put(
                                    "bodyBase64",
                                    Base64.getEncoder()
                                            .encodeToString("uploaded fixture".getBytes(StandardCharsets.UTF_8))),
                    Duration.ofSeconds(20));
            page = channel.call(
                    "browser/action",
                    json.createObjectNode()
                            .put("operation", "select")
                            .put("reference", reference(page, "Choice"))
                            .put("value", "two"),
                    Duration.ofSeconds(20));
            step.set("download");
            channel.call(
                    "browser/action",
                    json.createObjectNode().put("operation", "click").put("reference", reference(page, "Download")),
                    Duration.ofSeconds(20));
            for (int attempt = 0; attempt < 20; attempt++) {
                page = channel.call(
                        "browser/action",
                        json.createObjectNode().put("operation", "wait").put("value", "100"),
                        Duration.ofSeconds(5));
                if (!page.path("downloads").isEmpty()) {
                    break;
                }
            }
            assertEquals(1, page.path("downloads").size());
            assertEquals(0, downloadRequests.get(), "Chromium 原生下载不经过 route，因此沙箱必须阻止它");
            var downloaded = channel.call(
                    "browser/action",
                    json.createObjectNode()
                            .put("operation", "downloadLink")
                            .put("reference", reference(page, "Download")),
                    Duration.ofSeconds(20));
            assertEquals(1, downloadRequests.get(), "显式下载必须使用 Broker，不重试原始点击或表单");
            assertEquals(
                    "download fixture",
                    new String(
                            Base64.getDecoder()
                                    .decode(downloaded.path("bodyBase64").asText()),
                            StandardCharsets.UTF_8));
            step.set("tabs");
            var second = channel.call(
                    "browser/action",
                    json.createObjectNode().put("operation", "newTab").put("value", "https://example.test/next"),
                    Duration.ofSeconds(20));
            assertEquals(2, second.path("tabs").size());
            page = channel.call(
                    "browser/action",
                    json.createObjectNode()
                            .put("operation", "closeTab")
                            .put("tabId", second.path("tabId").asText()),
                    Duration.ofSeconds(20));
            assertEquals(1, page.path("tabs").size());
            step.set("fill");
            String nameRef = reference(page, "Name");
            var filled = channel.call(
                    "browser/action",
                    json.createObjectNode()
                            .put("operation", "fill")
                            .put("reference", nameRef)
                            .put("value", "Ada"),
                    Duration.ofSeconds(20));
            assertThrows(
                    Exception.class,
                    () -> channel.call(
                            "browser/action",
                            json.createObjectNode()
                                    .put("operation", "fill")
                                    .put("reference", nameRef)
                                    .put("value", "stale"),
                            Duration.ofSeconds(20)));
            String password = reference(filled, "Password");
            step.set("secret fill");
            assertThrows(
                    Exception.class,
                    () -> channel.call(
                            "browser/action",
                            json.createObjectNode()
                                    .put("operation", "fill")
                                    .put("reference", password)
                                    .put("value", "secret"),
                            Duration.ofSeconds(20)));
            var secured = channel.call(
                    "browser/action",
                    json.createObjectNode()
                            .put("operation", "fill")
                            .put("reference", password)
                            .put("value", "fixture-secret")
                            .put("secret", true),
                    Duration.ofSeconds(20));
            assertFalse(secured.toString().contains("fixture-secret"));
            step.set("screenshot");
            var screenshot = channel.call(
                    "browser/action", json.createObjectNode().put("operation", "screenshot"), Duration.ofSeconds(20));
            assertTrue(Base64.getDecoder().decode(screenshot.path("bodyBase64").asText()).length > 100);
            step.set("submit");
            // 截图遮罩可以改变 DOM 形态；重新读取页面后再操作，不能绕过生产环境的陈旧引用校验。
            var current = channel.call(
                    "browser/action", json.createObjectNode().put("operation", "snapshot"), Duration.ofSeconds(20));
            var sent = channel.call(
                    "browser/action",
                    json.createObjectNode().put("operation", "click").put("reference", reference(current, "Send")),
                    Duration.ofSeconds(20));
            assertTrue(sent.path("text").asText().contains("Submitted once"));
            assertEquals(1, methods.stream().filter("POST"::equals).count());
            step.set("state");
            assertThrows(
                    Exception.class,
                    () -> channel.call(
                            "browser/action",
                            json.createObjectNode().put("operation", "evaluate").put("value", "document.cookie"),
                            Duration.ofSeconds(20)));
            var state = channel.call("browser/state", json.createObjectNode(), Duration.ofSeconds(10));
            assertTrue(json.readTree(state.path("state").asText()).has("cookies"));
            assertTrue(state.path("state").asText().contains("login-state"));
            step.set("manual login window");
            channel.call("browser/close", json.createObjectNode(), Duration.ofSeconds(10));
            var login = channel.call(
                    "browser/open",
                    json.createObjectNode()
                            .put("url", "https://example.test/")
                            .put("headed", true)
                            .put("state", state.path("state").asText()),
                    Duration.ofSeconds(30));
            assertEquals("Browser fixture", login.path("title").asText());
            channel.call("browser/close", json.createObjectNode(), Duration.ofSeconds(10));
        } catch (Exception failure) {
            // 只打印本测试的合成网页诊断；生产 Channel 继续丢弃原始浏览器 stderr。
            throw new AssertionError(
                    "native Browser fixture failed at " + step.get() + "\n" + String.join("", diagnostics), failure);
        }
    }

    private static String reference(JsonNode page, String label) {
        for (var element : page.path("elements")) {
            if (element.path("label").asText().equals(label)) {
                return element.path("reference").asText();
            }
        }
        throw new AssertionError("missing page reference " + label);
    }

    private static Path location(Class<?> type) throws Exception {
        return Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI());
    }
}
