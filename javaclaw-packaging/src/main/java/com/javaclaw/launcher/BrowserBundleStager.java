package com.javaclaw.launcher;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.jar.JarFile;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** 发行期浏览器资源归档；只复制匹配本包 driver 清单的缓存版本，不下载、不运行浏览器、不合并用户配置。 */
public final class BrowserBundleStager {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> REQUIRED = Set.of("chromium", "chromium-headless-shell", "ffmpeg");
    private static final long MAXIMUM_BYTES = 3L * 1024 * 1024 * 1024;
    private static final int MAXIMUM_FILES = 50_000;

    private BrowserBundleStager() {}

    /** Maven 内部入口：stage library 或 sbom distribution；所有目标都必须是本次构建拥有的新目录。 缺少精确版本、安装完成标记或许可证时失败，不偷偷使用另一个用户缓存版本。 */
    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            throw new IllegalArgumentException("usage: BrowserBundleStager stage|sbom <build-directory>");
        }
        switch (args[0]) {
            case "stage" ->
                stage(
                        Path.of(args[1]),
                        cache(
                                System.getenv(),
                                Path.of(System.getProperty("javaclaw.program.dir", System.getProperty("user.dir", ".")))
                                        .toAbsolutePath()
                                        .normalize()),
                        System.getProperty("os.name"),
                        System.getProperty("os.arch"));
            case "sbom" -> augmentSbom(Path.of(args[1]));
            default -> throw new IllegalArgumentException("unsupported browser bundle operation");
        }
    }

    static Path cache(Map<String, String> environment, Path programDirectory) {
        String explicit = environment.getOrDefault(
                "JAVACLAW_BROWSER_ASSET_DIR", environment.getOrDefault("PLAYWRIGHT_BROWSERS_PATH", ""));
        if (!explicit.isBlank()) {
            if (explicit.equals("0")) {
                throw new IllegalArgumentException("hermetic driver cache is not a distribution source");
            }
            return Path.of(explicit).toAbsolutePath().normalize();
        }
        return programDirectory.toAbsolutePath().normalize().resolve(".javaclaw/cache-v4/ms-playwright");
    }

    static void stage(Path library, Path cache, String osName, String arch) throws Exception {
        Path root = library.toRealPath();
        Path source = cache.toRealPath();
        Path target = root.resolve("ms-playwright");
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException(
                    "browser bundle destination already exists; clean the owned distribution stage first");
        }
        Path driver = root.resolve("com.microsoft.playwright.driver-bundle.jar");
        var platform = platform(osName, arch);
        JsonNode manifest;
        byte[] driverLicense;
        try (var jar = new JarFile(driver.toFile())) {
            manifest = JSON.readTree(read(jar, "driver/" + platform.driver() + "/package/browsers.json"));
            driverLicense = read(jar, "driver/" + platform.driver() + "/LICENSE");
        }
        var selected = new ArrayList<Bundle>();
        for (var browser : manifest.path("browsers")) {
            String name = browser.path("name").asText();
            if (!REQUIRED.contains(name) && !(platform.os().equals("win") && name.equals("winldd"))) {
                continue;
            }
            String revision = browser.path("revisionOverrides")
                    .path(platform.override())
                    .asText(browser.path("revision").asText());
            if (!revision.matches("[0-9]{1,10}")) {
                throw new IOException("invalid driver browser revision");
            }
            Path directory = source.resolve(name.replace('-', '_') + "-" + revision);
            Path executable = directory.resolve(executable(name, platform.os()));
            if (Files.isSymbolicLink(directory)
                    || !Files.isDirectory(directory)
                    || !Files.isRegularFile(directory.resolve("INSTALLATION_COMPLETE"), LinkOption.NOFOLLOW_LINKS)
                    || !Files.isRegularFile(executable)
                    || !executable.toRealPath().startsWith(directory.toRealPath())
                    || (!platform.os().equals("win") && !Files.isExecutable(executable))) {
                throw new IOException("missing or incomplete " + name + "-" + revision
                        + "; explicitly provision browsers with -Djavaclaw.browser.install");
            }
            selected.add(
                    new Bundle(name, revision, browser.path("browserVersion").asText(revision), directory, executable));
        }
        Set<String> required = platform.os().equals("win")
                ? Set.of("chromium", "chromium-headless-shell", "ffmpeg", "winldd")
                : REQUIRED;
        if (!selected.stream()
                .map(Bundle::name)
                .collect(java.util.stream.Collectors.toSet())
                .containsAll(required)) {
            throw new IOException("driver does not declare the required browser set");
        }
        Path credits = source.resolve("javaclaw-chromium-"
                + selected.stream()
                        .filter(value -> value.name().equals("chromium"))
                        .findFirst()
                        .orElseThrow()
                        .revision()
                + "-credits.html");
        if (!Files.isRegularFile(credits, LinkOption.NOFOLLOW_LINKS)
                || Files.size(credits) < 100
                || Files.size(credits) > 16 * 1024 * 1024) {
            throw new IOException(
                    "browser third-party notices are missing; run the explicit browser provisioning profile");
        }
        Files.createDirectory(target);
        var inventory = new TreeMap<String, ObjectNode>();
        var limits = new Limits();
        var components = JSON.createArrayNode();
        for (var bundle : selected.stream()
                .sorted(java.util.Comparator.comparing(Bundle::name))
                .toList()) {
            Path destination = target.resolve(bundle.directory().getFileName());
            copyTree(bundle.directory(), destination, target, inventory, limits);
            components
                    .addObject()
                    .put("name", bundle.name())
                    .put("version", bundle.version())
                    .put("revision", bundle.revision())
                    .put("directory", target.relativize(destination).toString().replace('\\', '/'))
                    .put(
                            "executable",
                            target.relativize(destination.resolve(
                                            bundle.directory().relativize(bundle.executable())))
                                    .toString()
                                    .replace('\\', '/'));
        }
        Path notices = Files.createDirectory(target.resolve("licenses"));
        Files.write(notices.resolve("Playwright-Node-LICENSE.txt"), driverLicense);
        Files.copy(credits, notices.resolve("Chromium-CREDITS.html"));
        var description = JSON.createObjectNode()
                .put("format", 1)
                .put("platform", platform.driver())
                .put("driverSha256", sha256(driver));
        description.set("components", components);
        var files = description.putArray("files");
        inventory.values().forEach(files::add);
        description.put("chromiumCreditsSha256", sha256(notices.resolve("Chromium-CREDITS.html")));
        Files.writeString(
                target.resolve("bundle.json"),
                JSON.writerWithDefaultPrettyPrinter().writeValueAsString(description) + "\n",
                StandardCharsets.UTF_8);
        System.out.println("Browser bundle staged: " + platform.driver() + ", " + inventory.size() + " entries, "
                + limits.bytes + " bytes");
    }

    private static byte[] read(JarFile jar, String name) throws IOException {
        var entry = jar.getJarEntry(name);
        if (entry == null || entry.getSize() > 1024 * 1024) {
            throw new IOException("missing or oversized driver resource " + name);
        }
        try (var input = jar.getInputStream(entry)) {
            byte[] bytes = input.readNBytes(1024 * 1024 + 1);
            if (bytes.length > 1024 * 1024) {
                throw new IOException("driver resource exceeds limit");
            }
            return bytes;
        }
    }

    private static void copyTree(
            Path source, Path destination, Path bundleRoot, Map<String, ObjectNode> inventory, Limits limits)
            throws IOException {
        Path realSource = source.toRealPath();
        Files.walkFileTree(source, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes)
                    throws IOException {
                if (!directory.toRealPath().startsWith(realSource)) {
                    throw new IOException("browser bundle directory escapes its component");
                }
                if (++limits.files > MAXIMUM_FILES) {
                    throw new IOException("browser bundle has too many entries");
                }
                Files.createDirectory(destination.resolve(source.relativize(directory)));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                if (++limits.files > MAXIMUM_FILES || (limits.bytes += attributes.size()) > MAXIMUM_BYTES) {
                    throw new IOException("browser bundle exceeds resource limit");
                }
                Path relative = source.relativize(file);
                Path copied = destination.resolve(relative);
                String name = bundleRoot.relativize(copied).toString().replace('\\', '/');
                var item = JSON.createObjectNode().put("path", name);
                if (Files.isSymbolicLink(file)) {
                    Path link = Files.readSymbolicLink(file);
                    if (link.isAbsolute()
                            || !file.toRealPath().startsWith(realSource)
                            || !file.getParent().resolve(link).normalize().startsWith(source)) {
                        throw new IOException("browser bundle symlink escapes its component: " + relative);
                    }
                    Files.createSymbolicLink(copied, link);
                    item.put("link", link.toString().replace('\\', '/'));
                } else {
                    if (!attributes.isRegularFile()) {
                        throw new IOException("browser bundle contains a special file");
                    }
                    Files.copy(file, copied, StandardCopyOption.COPY_ATTRIBUTES, LinkOption.NOFOLLOW_LINKS);
                    item.put("size", Files.size(copied))
                            .put("sha256", sha256(copied))
                            .put("executable", Files.isExecutable(copied));
                }
                inventory.put(name, item);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    static void augmentSbom(Path distribution) throws Exception {
        var bundle = JSON.readTree(
                distribution.resolve("lib/ms-playwright/bundle.json").toFile());
        Path file = distribution.resolve("sbom.json");
        ObjectNode sbom = (ObjectNode) JSON.readTree(file.toFile());
        var components = sbom.withArray("components");
        for (var browser : bundle.path("components")) {
            String reference = "javaclaw-browser:" + browser.path("name").asText() + ":"
                    + browser.path("revision").asText();
            if (java.util.stream.StreamSupport.stream(components.spliterator(), false)
                    .anyMatch(value -> reference.equals(value.path("bom-ref").asText()))) {
                throw new IOException("duplicate browser SBOM component");
            }
            var component = components
                    .addObject()
                    .put("type", "application")
                    .put("bom-ref", reference)
                    .put("name", browser.path("name").asText())
                    .put("version", browser.path("version").asText());
            component
                    .putArray("properties")
                    .addObject()
                    .put("name", "javaclaw:playwright-revision")
                    .put("value", browser.path("revision").asText());
            component
                    .withArray("properties")
                    .addObject()
                    .put("name", "javaclaw:bundle-inventory")
                    .put("value", "lib/ms-playwright/bundle.json");
            component
                    .putArray("hashes")
                    .addObject()
                    .put("alg", "SHA-256")
                    .put(
                            "content",
                            sha256(distribution
                                    .resolve("lib/ms-playwright")
                                    .resolve(browser.path("executable").asText())));
        }
        Files.writeString(
                file, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(sbom) + "\n", StandardCharsets.UTF_8);
        Files.writeString(
                distribution.resolve("THIRD-PARTY.txt"),
                "\nBrowser components and exact revisions: lib/ms-playwright/bundle.json\n"
                        + "Chromium and embedded third-party notices: lib/ms-playwright/licenses/Chromium-CREDITS.html\n"
                        + "Playwright/Node notices: lib/ms-playwright/licenses/Playwright-Node-LICENSE.txt\n"
                        + "FFmpeg LGPL notice: lib/ms-playwright/ffmpeg-*/COPYING.LGPLv2.1\n",
                StandardCharsets.UTF_8,
                java.nio.file.StandardOpenOption.APPEND);
    }

    private static Platform platform(String name, String architecture) {
        String os = name.toLowerCase(Locale.ROOT);
        boolean arm = Set.of("aarch64", "arm64").contains(architecture.toLowerCase(Locale.ROOT));
        if (!arm && !Set.of("amd64", "x86_64", "x64").contains(architecture.toLowerCase(Locale.ROOT))) {
            throw new IllegalArgumentException("unsupported browser architecture");
        }
        if (os.contains("mac")) {
            String version = System.getProperty("os.version").split("\\.")[0];
            return new Platform("mac", arm ? "mac-arm64" : "mac", "mac" + version + (arm ? "-arm64" : ""));
        }
        if (os.contains("windows") && !arm) {
            return new Platform("win", "win32_x64", "win64");
        }
        if (os.contains("linux")) {
            return new Platform("linux", arm ? "linux-arm64" : "linux", "");
        }
        throw new IllegalArgumentException("unsupported browser platform");
    }

    private static String executable(String name, String os) {
        return switch (name) {
            case "chromium" ->
                switch (os) {
                    case "mac" -> "chrome-mac/Chromium.app/Contents/MacOS/Chromium";
                    case "win" -> "chrome-win/chrome.exe";
                    default -> "chrome-linux/chrome";
                };
            case "chromium-headless-shell" ->
                os.equals("win") ? "chrome-win/headless_shell.exe" : "chrome-" + os + "/headless_shell";
            case "ffmpeg" -> os.equals("win") ? "ffmpeg-win64.exe" : "ffmpeg-" + os;
            case "winldd" -> "PrintDeps.exe";
            default -> throw new IllegalArgumentException("unknown browser component");
        };
    }

    static String sha256(Path path) throws IOException {
        try (var input = Files.newInputStream(path)) {
            var digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[64 * 1024];
            for (int count; (count = input.read(buffer)) != -1; ) {
                digest.update(buffer, 0, count);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private record Platform(String os, String driver, String override) {}

    private record Bundle(String name, String revision, String version, Path directory, Path executable) {}

    private static final class Limits {
        private long bytes;
        private int files;
    }
}
