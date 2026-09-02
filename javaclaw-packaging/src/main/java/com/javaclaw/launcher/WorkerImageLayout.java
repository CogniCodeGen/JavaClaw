package com.javaclaw.launcher;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/** 启动器对签名 Worker image 的最小完整性检查；缺失 image 返回不可用，畸形或越界 image 拒绝启动。 */
final class WorkerImageLayout {
    private static final String IMAGE_MARKER = "worker-image-v1.capability";
    private static final Set<String> TYPES = Set.of("browser", "knowledge", "skill");
    private static final Set<String> MAIN_FORBIDDEN_PREFIXES = Set.of(
            "driver-",
            "driver-bundle-",
            "fontbox-",
            "javaclaw-knowledge-worker-",
            "pdfbox-",
            "playwright-",
            "poi-",
            "poi-ooxml-",
            "xmlbeans-");

    private WorkerImageLayout() {}

    static Optional<Path> discover(Path distributionRoot, String type) {
        String checkedType = type.toLowerCase(Locale.ROOT);
        if (!TYPES.contains(checkedType)) {
            throw new IllegalArgumentException("unknown Worker image type: " + type);
        }
        Path configured =
                distributionRoot.resolve("workers").resolve(checkedType).normalize();
        Path marker = configured.resolve(IMAGE_MARKER).normalize();
        if (!Files.exists(marker, LinkOption.NOFOLLOW_LINKS)) {
            return Optional.empty();
        }
        try {
            if (Files.isSymbolicLink(configured)) {
                throw new IOException("Worker image root is a symbolic link");
            }
            Path root = configured.toRealPath();
            requireInside(distributionRoot, root, "Worker image");
            requireMarker(root, marker, "worker-image-v1:" + checkedType);
            verifyLayout(root, checkedType);
            return Optional.of(root);
        } catch (IOException failure) {
            throw new IllegalStateException(checkedType + " Worker image is invalid", failure);
        }
    }

    static void requireMainLibraryIsolation(Path libraryDirectory) {
        try (Stream<Path> entries = Files.list(libraryDirectory)) {
            Optional<String> forbidden = entries.filter(Files::isRegularFile)
                    .map(path -> path.getFileName().toString().toLowerCase(Locale.ROOT))
                    .filter(WorkerImageLayout::forbiddenInMainLibrary)
                    .findFirst();
            if (forbidden.isPresent()) {
                throw new IllegalStateException("Worker private dependency leaked into main lib: " + forbidden.get());
            }
        } catch (IOException failure) {
            throw new IllegalStateException("main library isolation cannot be verified", failure);
        }
    }

    private static boolean forbiddenInMainLibrary(String fileName) {
        return MAIN_FORBIDDEN_PREFIXES.stream().anyMatch(fileName::startsWith);
    }

    private static void verifyLayout(Path root, String type) throws IOException {
        String suffix = RuntimeLayout.isWindows() ? ".exe" : "";
        requireExecutable(root, root.resolve("bin/java" + suffix), "Worker Java");
        if (type.equals("skill")) {
            requireExecutable(root, root.resolve("bin/jshell" + suffix), "Skill JShell");
            return;
        }
        Path app = root.resolve("app").toRealPath(LinkOption.NOFOLLOW_LINKS);
        requireInside(root, app, "Worker app");
        requireEntrypoint(app, type);
        if (type.equals("browser")) {
            requireArtifact(app, "playwright-1.52.0", "Browser Worker Playwright");
            rejectArtifacts(app, Set.of("pdfbox-", "poi-", "javaclaw-knowledge-worker-"), "Browser Worker");
            verifyBrowser(root);
            return;
        }
        requireArtifact(app, "pdfbox-", "Knowledge Worker PDFBox");
        requireArtifact(app, "poi-ooxml-", "Knowledge Worker POI");
        rejectArtifacts(app, Set.of("playwright-", "driver-", "javaclaw-browser-service-"), "Knowledge Worker");
    }

    private static void verifyBrowser(Path root) throws IOException {
        Path browser = root.resolve("browser").toRealPath(LinkOption.NOFOLLOW_LINKS);
        requireInside(root, browser, "Browser runtime");
        requireMarker(browser, browser.resolve(".javaclaw-playwright-version"), "playwright:1.52.0");
        try (Stream<Path> paths = Files.walk(browser)) {
            if (paths.filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                    .map(path -> path.getFileName().toString())
                    .noneMatch(WorkerImageLayout::chromiumExecutableName)) {
                throw new IOException("Browser runtime does not contain Chromium");
            }
        }
    }

    private static boolean chromiumExecutableName(String name) {
        return Set.of("chrome", "chrome.exe", "Chromium", "headless_shell", "headless_shell.exe")
                .contains(name);
    }

    private static void requireEntrypoint(Path app, String type) throws IOException {
        String prefix = type.equals("browser") ? "javaclaw-browser-service-" : "javaclaw-knowledge-worker-";
        try (Stream<Path> entries = Files.list(app)) {
            if (entries.filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                    .map(path -> path.getFileName().toString())
                    .noneMatch(name -> name.startsWith(prefix) && name.endsWith(".jar"))) {
                throw new IOException(type + " Worker entrypoint jar is missing");
            }
        }
    }

    private static void requireArtifact(Path app, String prefix, String name) throws IOException {
        try (Stream<Path> entries = Files.list(app)) {
            if (entries.filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                    .map(path -> path.getFileName().toString())
                    .noneMatch(fileName -> fileName.startsWith(prefix) && fileName.endsWith(".jar"))) {
                throw new IOException(name + " dependency is missing");
            }
        }
    }

    private static void rejectArtifacts(Path app, Set<String> prefixes, String name) throws IOException {
        try (Stream<Path> entries = Files.list(app)) {
            if (entries.filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                    .map(path -> path.getFileName().toString())
                    .anyMatch(fileName -> prefixes.stream().anyMatch(fileName::startsWith))) {
                throw new IOException(name + " contains a dependency owned by another process");
            }
        }
    }

    private static void requireMarker(Path root, Path marker, String expected) throws IOException {
        if (Files.isSymbolicLink(marker) || !Files.isRegularFile(marker, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Worker marker is missing or unsafe");
        }
        Path real = marker.toRealPath();
        requireInside(root, real, "Worker marker");
        if (!expected.equals(Files.readString(real, StandardCharsets.US_ASCII).strip())) {
            throw new IOException("Worker marker does not match its image");
        }
    }

    private static void requireExecutable(Path root, Path executable, String name) throws IOException {
        Path real = executable.toRealPath();
        requireInside(root, real, name);
        if (!Files.isRegularFile(real) || !Files.isExecutable(real)) {
            throw new IOException(name + " is missing or not executable");
        }
    }

    private static void requireInside(Path root, Path candidate, String name) throws IOException {
        Path realRoot = root.toRealPath();
        if (!candidate.startsWith(realRoot)) {
            throw new IOException(name + " escapes its distribution root");
        }
    }
}
