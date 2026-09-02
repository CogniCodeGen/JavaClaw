package com.javaclaw.server.extension;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.javaclaw.api.ResourceLimits;
import com.javaclaw.browser.client.BrowserWorkerCapabilities;
import com.javaclaw.browser.client.BrowserWorkerClient;
import com.javaclaw.nativehost.sandbox.SandboxedWorkerCommand;

/** 从签名发行镜像的固定布局创建 Browser Worker；IDEA classpath 不会被隐式授予。 */
final class BrowserWorkerRuntimeFactory {
    static final String IMAGE_ROOT_PROPERTY = "javaclaw.browser.worker.image-root";
    private static final Duration TIMEOUT = Duration.ofSeconds(45);
    private static final Duration WORKER_LIFETIME = Duration.ofMinutes(10);
    private static final String LOGIN_CAPABILITY_FILE = "browser-login-v1.capability";
    private static final String OAUTH_CAPABILITY_FILE = "browser-oauth-v1.capability";
    private static final String IMAGE_CAPABILITY_FILE = "worker-image-v1.capability";
    private static final String PLAYWRIGHT_VERSION_FILE = ".javaclaw-playwright-version";

    private BrowserWorkerRuntimeFactory() {}

    static Optional<BrowserWorkerClient> create(Path dataRoot) {
        String configured = System.getProperty(IMAGE_ROOT_PROPERTY, "").strip();
        if (configured.isEmpty()) {
            return Optional.empty();
        }
        try {
            Path imageRoot = Path.of(configured).toRealPath();
            Layout layout = layout(imageRoot, dataRoot);
            boolean displayAvailable = layout.display().loginAvailable();
            return Optional.of(new BrowserWorkerClient(
                    command(layout),
                    TIMEOUT,
                    layout.control(),
                    new BrowserWorkerCapabilities(
                            capabilityVerified(layout.imageRoot(), LOGIN_CAPABILITY_FILE, "browser-login-v1")
                                    && displayAvailable,
                            capabilityVerified(layout.imageRoot(), OAUTH_CAPABILITY_FILE, "browser-oauth-v1")
                                    && displayAvailable)));
        } catch (IOException failure) {
            throw new IllegalStateException("Browser Worker packaged runtime layout is invalid", failure);
        }
    }

    private static Layout layout(Path imageRoot, Path dataRoot) throws IOException {
        String javaName = System.getProperty("os.name", "")
                        .toLowerCase(java.util.Locale.ROOT)
                        .contains("windows")
                ? "java.exe"
                : "java";
        Path java = imageRoot.resolve("bin").resolve(javaName).toRealPath();
        Path app = imageRoot.resolve("app").toRealPath();
        Path browser = imageRoot.resolve("browser").toRealPath();
        requireInside(imageRoot, java, "Java runtime");
        requireInside(imageRoot, app, "Worker classpath");
        requireInside(imageRoot, browser, "Browser runtime");
        if (!Files.isExecutable(java) || !Files.isDirectory(app) || !Files.isDirectory(browser)) {
            throw new IOException("Browser Worker image is incomplete");
        }
        requireMarker(imageRoot.resolve(IMAGE_CAPABILITY_FILE), "worker-image-v1:browser");
        requireMarker(browser.resolve(PLAYWRIGHT_VERSION_FILE), "playwright:1.52.0");
        Path data = dataRoot.toRealPath();
        Path work = data.resolve("browser-worker").resolve("tmp");
        Path control = data.resolve("browser-worker").resolve("control");
        Files.createDirectories(work);
        Files.createDirectories(control);
        work = work.toRealPath();
        control = control.toRealPath();
        requireInside(data, work, "Worker temporary directory");
        requireInside(data, control, "Browser login control directory");
        return new Layout(imageRoot, java, app, browser, work, control, displayAccess());
    }

    private static SandboxedWorkerCommand command(Layout layout) {
        String classpath = layout.app().resolve("*").toString();
        List<String> argv = List.of(
                layout.java().toString(),
                "-XX:-UsePerfData",
                "-cp",
                classpath,
                "com.javaclaw.browser.worker.BrowserWorkerMain");
        LinkedHashMap<String, String> environment = new LinkedHashMap<>();
        environment.put("JAVACLAW_BROWSER_CONTROL_ROOT", layout.control().toString());
        environment.put("PLAYWRIGHT_BROWSERS_PATH", layout.browser().toString());
        environment.put("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD", "1");
        environment.put("TMPDIR", layout.work().toString());
        environment.putAll(layout.display().environment());
        ArrayList<Path> readRoots = new ArrayList<>(List.of(layout.imageRoot(), layout.control()));
        readRoots.addAll(layout.display().readRoots());
        return new SandboxedWorkerCommand(
                "browser-worker",
                argv,
                layout.work(),
                environment,
                readRoots,
                List.of(layout.work()),
                List.of(layout.java(), layout.browser()),
                WORKER_LIFETIME,
                new ResourceLimits(1024L * 1024 * 1024, 16L * 1024 * 1024, 16, 512));
    }

    static boolean capabilityVerified(Path imageRoot, String fileName, String prefix) {
        try {
            Path marker = imageRoot.resolve(fileName).normalize();
            if (!marker.getParent().equals(imageRoot) || Files.isSymbolicLink(marker) || !Files.isRegularFile(marker)) {
                return false;
            }
            Path real = marker.toRealPath();
            requireInside(imageRoot, real, "Browser capability marker");
            String expected = prefix + ":" + platformId();
            return expected.equals(Files.readString(real, java.nio.charset.StandardCharsets.US_ASCII)
                    .strip());
        } catch (IOException | SecurityException unavailable) {
            return false;
        }
    }

    private static DisplayAccess displayAccess() throws IOException {
        if (!platformId().equals("linux")) {
            return new DisplayAccess(Map.of(), List.of(), true);
        }
        String display = System.getenv().getOrDefault("DISPLAY", "").strip();
        if (!display.matches(":[0-9]+(?:\\.[0-9]+)?")) {
            return new DisplayAccess(Map.of(), List.of(), false);
        }
        int separator = display.indexOf('.');
        String number = display.substring(1, separator < 0 ? display.length() : separator);
        Path socketRoot = Path.of("/tmp/.X11-unix");
        if (!Files.isDirectory(socketRoot) || !Files.exists(socketRoot.resolve("X" + number))) {
            return new DisplayAccess(Map.of(), List.of(), false);
        }
        LinkedHashMap<String, String> environment = new LinkedHashMap<>();
        environment.put("DISPLAY", display);
        ArrayList<Path> readRoots = new ArrayList<>(List.of(socketRoot.toRealPath()));
        String authority = System.getenv().getOrDefault("XAUTHORITY", "").strip();
        if (!authority.isEmpty()) {
            Path file = Path.of(authority).toRealPath();
            if (!Files.isRegularFile(file)) {
                return new DisplayAccess(Map.of(), List.of(), false);
            }
            environment.put("XAUTHORITY", file.toString());
            readRoots.add(file);
        }
        return new DisplayAccess(environment, readRoots, true);
    }

    private static void requireMarker(Path marker, String expected) throws IOException {
        if (Files.isSymbolicLink(marker) || !Files.isRegularFile(marker, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Browser Worker image marker is missing or unsafe");
        }
        if (!expected.equals(Files.readString(marker, java.nio.charset.StandardCharsets.US_ASCII)
                .strip())) {
            throw new IOException("Browser Worker image marker does not match its locked dependency");
        }
    }

    private static String platformId() {
        String os = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT);
        if (os.contains("mac")) {
            return "macos";
        }
        if (os.contains("win")) {
            return "windows";
        }
        return "linux";
    }

    private static void requireInside(Path root, Path candidate, String name) throws IOException {
        if (!candidate.startsWith(root)) {
            throw new IOException(name + " escapes its configured root");
        }
    }

    private record Layout(
            Path imageRoot, Path java, Path app, Path browser, Path work, Path control, DisplayAccess display) {}

    /** Linux 仅转交本地 X11 socket 与可选 authority；其他平台由原生窗口系统和 Sandbox 负责。 */
    private record DisplayAccess(Map<String, String> environment, List<Path> readRoots, boolean loginAvailable) {
        private DisplayAccess {
            environment = Map.copyOf(environment);
            readRoots = List.copyOf(readRoots);
        }
    }
}
