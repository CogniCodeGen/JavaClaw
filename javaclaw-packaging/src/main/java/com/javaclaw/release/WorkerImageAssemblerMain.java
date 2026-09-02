package com.javaclaw.release;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.CopyOption;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Comparator;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;

/** 构建并验证发行包中的独立 Worker image；不下载依赖，也不读取宿主开发 classpath。 */
public final class WorkerImageAssemblerMain {
    /** Worker image 类型标记文件名。 */
    public static final String IMAGE_MARKER = "worker-image-v1.capability";

    /** Playwright 离线 Browser 来源版本文件名。 */
    public static final String PLAYWRIGHT_MARKER = ".javaclaw-playwright-version";

    private static final Set<String> TYPES = Set.of("browser", "knowledge", "skill");

    private WorkerImageAssemblerMain() {}

    /**
     * 执行 Worker image 构建命令。
     *
     * @param arguments {@code browser SOURCE IMAGE_ROOT VERSION} 或 {@code mark IMAGE_ROOT TYPE}
     * @throws Exception 路径、版本、依赖或文件类型不符合发行边界
     */
    public static void main(String[] arguments) throws Exception {
        Objects.requireNonNull(arguments, "arguments");
        if (arguments.length == 4 && "browser".equals(arguments[0])) {
            copyBrowser(Path.of(arguments[1]), Path.of(arguments[2]), arguments[3]);
            return;
        }
        if (arguments.length == 3 && "mark".equals(arguments[0])) {
            mark(Path.of(arguments[1]), arguments[2]);
            return;
        }
        throw new IllegalArgumentException(
                "usage: browser SOURCE IMAGE_ROOT PLAYWRIGHT_VERSION | mark IMAGE_ROOT TYPE");
    }

    static void copyBrowser(Path source, Path imageRoot, String playwrightVersion) throws IOException {
        Path checkedSource = realDirectory(source, "Playwright Browser source");
        Path checkedImage = realDirectory(imageRoot, "Browser Worker image");
        Path destination = checkedImage.resolve("browser").normalize();
        requireDirectChild(checkedImage, destination, "Browser runtime directory");
        requireSeparate(checkedSource, checkedImage);
        verifyPlaywrightSource(checkedSource, playwrightVersion);
        resetEmptyDirectory(destination);
        copyTree(checkedSource, destination);
        if (!containsChromiumExecutable(destination)) {
            throw new IOException("Playwright source does not contain a Chromium executable");
        }
        mark(checkedImage, "browser");
    }

    static void mark(Path imageRoot, String type) throws IOException {
        Path checkedImage = realDirectory(imageRoot, "Worker image");
        String checkedType = Objects.requireNonNull(type, "type").strip().toLowerCase(Locale.ROOT);
        if (!TYPES.contains(checkedType)) {
            throw new IllegalArgumentException("unknown Worker image type: " + checkedType);
        }
        verifyImageLayout(checkedImage, checkedType);
        writeReadOnlyMarker(checkedImage.resolve(IMAGE_MARKER), "worker-image-v1:" + checkedType);
    }

    private static void verifyPlaywrightSource(Path source, String version) throws IOException {
        String expected = "playwright:" + requiredText(version, "playwrightVersion");
        Path marker = source.resolve(PLAYWRIGHT_MARKER).normalize();
        requireDirectChild(source, marker, "Playwright version marker");
        if (Files.isSymbolicLink(marker) || !Files.isRegularFile(marker, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Playwright source version marker is missing or unsafe");
        }
        String actual = Files.readString(marker, StandardCharsets.US_ASCII).strip();
        if (!expected.equals(actual)) {
            throw new IOException("Playwright Browser source version does not match " + expected);
        }
        if (!containsChromiumExecutable(source)) {
            throw new IOException("Playwright source does not contain a Chromium executable");
        }
    }

    private static void verifyImageLayout(Path imageRoot, String type) throws IOException {
        String executableSuffix = isWindows() ? ".exe" : "";
        requireExecutable(imageRoot, imageRoot.resolve("bin/java" + executableSuffix), "Worker Java");
        if ("skill".equals(type)) {
            requireExecutable(imageRoot, imageRoot.resolve("bin/jshell" + executableSuffix), "Skill JShell");
            return;
        }
        Path app = realDirectory(imageRoot.resolve("app"), "Worker app directory");
        String requiredArtifact = "browser".equals(type) ? "javaclaw-browser-service-" : "javaclaw-knowledge-worker-";
        try (Stream<Path> files = Files.list(app)) {
            if (files.noneMatch(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                    && path.getFileName().toString().startsWith(requiredArtifact)
                    && path.getFileName().toString().endsWith(".jar"))) {
                throw new IOException(type + " Worker app does not contain its entrypoint jar");
            }
        }
        if ("browser".equals(type)) {
            requireArtifact(app, "playwright-1.52.0", "Browser Worker Playwright");
            rejectArtifacts(app, Set.of("pdfbox-", "poi-", "javaclaw-knowledge-worker-"), "Browser Worker");
            Path browser = realDirectory(imageRoot.resolve("browser"), "Browser runtime directory");
            if (!containsChromiumExecutable(browser)) {
                throw new IOException("Browser Worker image does not contain Chromium");
            }
            return;
        }
        requireArtifact(app, "pdfbox-", "Knowledge Worker PDFBox");
        requireArtifact(app, "poi-ooxml-", "Knowledge Worker POI");
        rejectArtifacts(app, Set.of("playwright-", "driver-", "javaclaw-browser-service-"), "Knowledge Worker");
    }

    private static void requireArtifact(Path app, String prefix, String name) throws IOException {
        try (Stream<Path> files = Files.list(app)) {
            if (files.filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                    .map(path -> path.getFileName().toString())
                    .noneMatch(fileName -> fileName.startsWith(prefix) && fileName.endsWith(".jar"))) {
                throw new IOException(name + " dependency is missing");
            }
        }
    }

    private static void rejectArtifacts(Path app, Set<String> prefixes, String name) throws IOException {
        try (Stream<Path> files = Files.list(app)) {
            if (files.filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                    .map(path -> path.getFileName().toString())
                    .anyMatch(fileName -> prefixes.stream().anyMatch(fileName::startsWith))) {
                throw new IOException(name + " contains a dependency owned by another process");
            }
        }
    }

    private static void copyTree(Path source, Path destination) throws IOException {
        try (Stream<Path> paths = Files.walk(source)) {
            for (Path path :
                    paths.sorted(Comparator.comparingInt(Path::getNameCount)).toList()) {
                Path relative = source.relativize(path);
                Path target = destination.resolve(relative).normalize();
                if (!target.startsWith(destination)) {
                    throw new IOException("Playwright source entry escapes its destination");
                }
                copyEntry(source, path, target);
            }
        }
    }

    private static void copyEntry(Path source, Path path, Path target) throws IOException {
        if (Files.isSymbolicLink(path)) {
            Path link = Files.readSymbolicLink(path);
            Path resolved = path.getParent().resolve(link).normalize();
            if (!resolved.startsWith(source)) {
                throw new IOException("Playwright source contains an escaping symbolic link");
            }
            Files.createSymbolicLink(target, link);
            return;
        }
        if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
            Files.createDirectories(target);
            return;
        }
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Playwright source contains an unsupported file type");
        }
        CopyOption[] options = {StandardCopyOption.COPY_ATTRIBUTES};
        Files.copy(path, target, options);
    }

    private static boolean containsChromiumExecutable(Path root) throws IOException {
        try (Stream<Path> paths = Files.walk(root)) {
            return paths.filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                    .anyMatch(WorkerImageAssemblerMain::isChromiumExecutable);
        }
    }

    private static boolean isChromiumExecutable(Path path) {
        String name = path.getFileName().toString();
        return switch (name) {
            case "chrome", "chrome.exe", "Chromium", "headless_shell", "headless_shell.exe" -> true;
            default -> false;
        };
    }

    private static void resetEmptyDirectory(Path directory) throws IOException {
        if (Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) {
            if (Files.isSymbolicLink(directory) || !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("Browser runtime destination is not a safe directory");
            }
            try (Stream<Path> entries = Files.list(directory)) {
                if (entries.findAny().isPresent()) {
                    throw new IOException("Browser runtime destination must be empty");
                }
            }
            return;
        }
        Files.createDirectory(directory);
    }

    private static void writeReadOnlyMarker(Path marker, String value) throws IOException {
        if (Files.exists(marker, LinkOption.NOFOLLOW_LINKS)) {
            if (Files.isSymbolicLink(marker) || !Files.isRegularFile(marker, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("Worker image marker is unsafe");
            }
            makeWritable(marker);
        }
        Path temporary = Files.createTempFile(marker.getParent(), ".worker-image-", ".tmp");
        try {
            Files.writeString(temporary, value + System.lineSeparator(), StandardCharsets.US_ASCII);
            Files.move(temporary, marker, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            setReadOnly(marker);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static void setReadOnly(Path marker) throws IOException {
        if (isWindows()) {
            Files.setAttribute(marker, "dos:readonly", true, LinkOption.NOFOLLOW_LINKS);
        } else {
            Files.setPosixFilePermissions(
                    marker,
                    Set.of(
                            PosixFilePermission.OWNER_READ,
                            PosixFilePermission.GROUP_READ,
                            PosixFilePermission.OTHERS_READ));
        }
    }

    private static void makeWritable(Path marker) throws IOException {
        if (isWindows()) {
            Files.setAttribute(marker, "dos:readonly", false, LinkOption.NOFOLLOW_LINKS);
            return;
        }
        Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(marker, LinkOption.NOFOLLOW_LINKS);
        java.util.HashSet<PosixFilePermission> writable = new java.util.HashSet<>(permissions);
        writable.add(PosixFilePermission.OWNER_WRITE);
        Files.setPosixFilePermissions(marker, writable);
    }

    private static void requireExecutable(Path root, Path executable, String name) throws IOException {
        Path real = executable.toRealPath();
        if (!real.startsWith(root) || !Files.isExecutable(real) || !Files.isRegularFile(real)) {
            throw new IOException(name + " is missing or escapes its image");
        }
    }

    private static Path realDirectory(Path value, String name) throws IOException {
        Path path = Objects.requireNonNull(value, name).toAbsolutePath().normalize();
        Path real = path.toRealPath();
        if (Files.isSymbolicLink(path) || !Files.isDirectory(real, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException(name + " is not a real directory");
        }
        return real;
    }

    private static void requireDirectChild(Path root, Path candidate, String name) throws IOException {
        if (!candidate.getParent().equals(root)) {
            throw new IOException(name + " is outside its fixed location");
        }
    }

    private static void requireSeparate(Path source, Path imageRoot) throws IOException {
        if (source.startsWith(imageRoot) || imageRoot.startsWith(source)) {
            throw new IOException("Playwright source and Worker image must be separate directories");
        }
    }

    private static String requiredText(String value, String name) {
        String checked = Objects.requireNonNull(value, name).strip();
        if (checked.isEmpty() || checked.length() > 40 || !checked.matches("[0-9]+(?:\\.[0-9]+){1,3}")) {
            throw new IllegalArgumentException(name + " is invalid");
        }
        return checked;
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("windows");
    }
}
