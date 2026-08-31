package com.javaclaw.server.browser;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;

import com.javaclaw.agent.runtime.persistence.AttachmentRepository;
import com.javaclaw.agent.tool.BrowserGateway;
import com.javaclaw.sandbox.api.BrokerRequest;
import com.javaclaw.sandbox.api.BrokerResponse;
import com.javaclaw.sandbox.api.NetworkBroker;
import com.javaclaw.sandbox.api.NetworkPolicy;
import com.javaclaw.sandbox.api.SandboxCommand;
import com.javaclaw.sandbox.api.SandboxExecutor;
import com.javaclaw.sandbox.api.SandboxMode;
import com.javaclaw.sandbox.api.SandboxPolicy;
import com.javaclaw.sandbox.api.SandboxSessionOptions;

/** Browser 辅助进程工厂和快照适配器；快照与交互操作共用有界管道及同一 Supervisor，不向进程开放原始网络。 */
public final class BrowserServiceRuntime implements BrowserGateway, AutoCloseable {
    private final NetworkBroker broker;
    private final AttachmentRepository attachments;
    private final SandboxExecutor sandbox;
    private final SandboxPolicy ceiling;
    private final List<String> command;
    private final Path runtimeRoot;
    private final Set<Path> infrastructureRoots;
    private final ObjectMapper json;
    private final Set<BrowserWorkerChannel> channels = new LinkedHashSet<>();
    private BrowserWorkerChannel renderer;
    private boolean closed;

    /** 固定打包 argv、数据和安全根；创建私有缓存目录，构造失败不启动降级进程。 */
    public BrowserServiceRuntime(
            NetworkBroker broker,
            AttachmentRepository attachments,
            SandboxExecutor sandbox,
            SandboxPolicy ceiling,
            List<String> command,
            Path runtimeRoot,
            Set<Path> infrastructureRoots,
            ObjectMapper json)
            throws IOException {
        this.broker = Objects.requireNonNull(broker);
        this.attachments = Objects.requireNonNull(attachments);
        this.sandbox = Objects.requireNonNull(sandbox);
        this.ceiling = Objects.requireNonNull(ceiling);
        this.command = List.copyOf(command);
        if (command.isEmpty()) {
            throw new IllegalArgumentException("browser command is empty");
        }
        this.runtimeRoot = com.javaclaw.server.security.PrivateDirectories.create(runtimeRoot);
        this.infrastructureRoots = Set.copyOf(infrastructureRoots == null ? Set.of() : infrastructureRoots);
        this.json = Objects.requireNonNull(json);
    }

    @Override
    public synchronized Snapshot snapshot(URI uri, boolean screenshot, int maximumTextCharacters) throws Exception {
        URI target = validateUri(uri);
        if (maximumTextCharacters < 100 || maximumTextCharacters > 100_000) {
            throw new IllegalArgumentException("maximumTextCharacters must be 100-100000");
        }
        BrokerResponse fetched = broker.execute(
                new BrokerRequest(
                        "GET",
                        target,
                        Map.of(
                                "Accept",
                                "text/html,application/xhtml+xml;q=0.9,text/plain;q=0.5",
                                "User-Agent",
                                "JavaClaw/4.0 BrowserService"),
                        new byte[0],
                        Duration.ofSeconds(30),
                        512 * 1024,
                        5),
                new NetworkPolicy(NetworkPolicy.Mode.ALLOWLIST, Set.of(allowlistEntry(target))));
        String html = decodeText(fetched);
        var params = json.createObjectNode()
                .put("htmlBase64", Base64.getEncoder().encodeToString(html.getBytes(StandardCharsets.UTF_8)))
                .put("screenshot", screenshot)
                .put("maximumTextCharacters", maximumTextCharacters);
        if (renderer == null || !renderer.alive()) {
            renderer = openChannel(ignored -> {
                throw new IOException("static snapshots cannot open network requests");
            });
        }
        var rendered = renderer.call("browser/render", params, Duration.ofSeconds(20));
        String sha = null;
        if (rendered.hasNonNull("screenshotBase64")) {
            byte[] image =
                    Base64.getDecoder().decode(rendered.path("screenshotBase64").asText());
            if (image.length > 1536 * 1024) {
                throw new IOException("browser screenshot exceeds limit");
            }
            sha = attachments.put(new ByteArrayInputStream(image), "image/png").sha256();
        }
        return new Snapshot(
                fetched.finalUri(),
                fetched.statusCode(),
                rendered.path("title").asText(),
                rendered.path("text").asText(),
                sha);
    }

    synchronized BrowserWorkerChannel openChannel(BrowserWorkerChannel.BrokerHandler handler) throws Exception {
        if (closed) {
            throw new IllegalStateException("browser runtime is closed");
        }
        channels.removeIf(channel -> {
            if (channel.alive()) {
                return false;
            }
            channel.close();
            return true;
        });
        if (channels.size() >= 8) {
            throw new IllegalStateException("browser process quota exceeded");
        }
        Path directory = com.javaclaw.server.security.PrivateDirectories.create(
                runtimeRoot.resolve("session-" + UUID.randomUUID()));
        Path home = com.javaclaw.server.security.PrivateDirectories.create(directory.resolve("home"));
        Path temporary = com.javaclaw.server.security.PrivateDirectories.create(directory.resolve("tmp"));
        var reads = new LinkedHashSet<>(infrastructureRoots);
        reads.add(directory);
        var display = BrowserDisplay.discover(System.getProperty("os.name"), System.getenv(), directory);
        reads.addAll(display.readableRoots());
        var requested = new SandboxPolicy(
                SandboxMode.WORKSPACE_WRITE,
                reads,
                Set.of(directory),
                ceiling.protectedRoots(),
                NetworkPolicy.disabled(),
                Set.of(
                        "LANG",
                        "LC_ALL",
                        "HOME",
                        "TMPDIR",
                        "DISPLAY",
                        "XAUTHORITY",
                        "WAYLAND_DISPLAY",
                        "XDG_RUNTIME_DIR",
                        "PLAYWRIGHT_BROWSERS_PATH",
                        "PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD"),
                Duration.ofMinutes(30),
                128L * 1024 * 1024);
        var environment = new LinkedHashMap<String, String>();
        environment.put("LANG", "C.UTF-8");
        environment.put("LC_ALL", "C.UTF-8");
        environment.put("HOME", home.toString());
        environment.put("TMPDIR", temporary.toString());
        environment.putAll(display.environment());
        // 浏览器二进制随发行物校验，不允许 Worker 在只读缓存中抢锁、安装或自动下载另一个版本。
        environment.put("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD", "1");
        infrastructureRoots.stream()
                .filter(path -> java.nio.file.Files.isDirectory(path)
                        && path.getFileName() != null
                        && path.getFileName()
                                .toString()
                                .toLowerCase(Locale.ROOT)
                                .contains("playwright"))
                .findFirst()
                .ifPresent(path -> environment.put("PLAYWRIGHT_BROWSERS_PATH", path.toString()));
        var launch = new java.util.ArrayList<>(command);
        if (launch.contains("com.javaclaw.browser.BrowserServiceMain")) {
            // Playwright 的 Java driver 使用 java.io.tmpdir，而非只读取 TMPDIR；二者必须落在同一受控目录。
            launch.add(1, "-Djava.io.tmpdir=" + temporary);
        }
        var process = sandbox.openSession(
                new SandboxCommand(
                        "browser_" + UUID.randomUUID(),
                        launch,
                        directory,
                        environment,
                        ceiling.intersect(requested),
                        "",
                        SandboxCommand.AuxiliaryRole.BROWSER),
                SandboxSessionOptions.pipes());
        var channel = new BrowserWorkerChannel(process, handler, () -> {
            try {
                BrowserSessionFiles.remove(runtimeRoot, directory);
            } catch (IOException failure) {
                // 缓存删除失败不掩盖原执行结果；不在诊断中打印可能包含私密页面信息的路径。
                System.getLogger(BrowserServiceRuntime.class.getName())
                        .log(System.Logger.Level.WARNING, "已停止的 Browser 临时目录未能完整回收；保留所有者独占权限");
            }
        });
        try {
            var ready = channel.call(
                    "initialize", json.createObjectNode().put("protocolVersion", 1), Duration.ofSeconds(10));
            if (ready.path("protocolVersion").asInt() != 1
                    || !"disabled".equals(ready.path("network").asText())) {
                throw new IOException("Browser Service negotiated unsafe capabilities");
            }
            channels.add(channel);
            return channel;
        } catch (Exception failure) {
            channel.close();
            throw failure;
        }
    }

    static URI validateUri(URI uri) {
        URI value = Objects.requireNonNull(uri).normalize();
        if (!Set.of("http", "https")
                        .contains(
                                value.getScheme() == null
                                        ? ""
                                        : value.getScheme().toLowerCase(Locale.ROOT))
                || value.isOpaque()
                || value.getHost() == null
                || value.getUserInfo() != null
                || value.getFragment() != null) {
            throw new IllegalArgumentException("browser URL is invalid");
        }
        return value;
    }

    static String allowlistEntry(URI uri) {
        URI origin = com.javaclaw.server.network.NetworkGrantService.origin(uri);
        return origin.getRawAuthority();
    }

    static String origin(URI uri) {
        validateUri(uri);
        return com.javaclaw.server.network.NetworkGrantService.origin(uri).toString();
    }

    private static String decodeText(BrokerResponse response) throws IOException {
        String type = response.headers().getOrDefault("content-type", List.of()).stream()
                .findFirst()
                .orElse("");
        String media = type.split(";", 2)[0].strip().toLowerCase(Locale.ROOT);
        if (!(media.startsWith("text/")
                || media.equals("application/xhtml+xml")
                || media.equals("application/xml")
                || media.isEmpty())) {
            throw new IOException("browser snapshot expects textual content");
        }
        Charset charset = StandardCharsets.UTF_8;
        for (String part : type.split(";")) {
            String candidate = part.strip();
            if (candidate.toLowerCase(Locale.ROOT).startsWith("charset=")) {
                try {
                    charset = Charset.forName(candidate.substring(8).strip());
                } catch (RuntimeException ignored) {
                    charset = StandardCharsets.UTF_8;
                }
            }
        }
        String text = new String(response.body(), charset);
        if (text.getBytes(StandardCharsets.UTF_8).length > 512 * 1024) {
            throw new IOException("decoded HTML exceeds limit");
        }
        return text;
    }

    @Override
    public synchronized void close() {
        closed = true;
        channels.forEach(BrowserWorkerChannel::close);
        channels.clear();
    }
}
