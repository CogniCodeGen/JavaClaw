package com.javaclaw.launcher;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkerImageLayoutTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void 缺少Worker镜像时保持不可用且不回退开发Classpath() throws Exception {
        Path root = distribution();

        assertTrue(WorkerImageLayout.discover(root, "browser").isEmpty());
        assertTrue(WorkerImageLayout.discover(root, "knowledge").isEmpty());
        assertTrue(WorkerImageLayout.discover(root, "skill").isEmpty());
    }

    @Test
    void 三类完整镜像解析为固定发行路径() throws Exception {
        Path root = distribution();
        Path browser = browserImage(root);
        Path knowledge = knowledgeImage(root);
        Path skill = skillImage(root);

        assertEquals(
                browser.toRealPath(),
                WorkerImageLayout.discover(root, "browser").orElseThrow());
        assertEquals(
                knowledge.toRealPath(),
                WorkerImageLayout.discover(root, "knowledge").orElseThrow());
        assertEquals(
                skill.toRealPath(), WorkerImageLayout.discover(root, "skill").orElseThrow());
    }

    @Test
    void 标记被篡改或入口缺失时拒绝整个镜像() throws Exception {
        Path root = distribution();
        Path browser = browserImage(root);
        Files.writeString(browser.resolve("worker-image-v1.capability"), "worker-image-v1:knowledge");

        assertThrows(IllegalStateException.class, () -> WorkerImageLayout.discover(root, "browser"));

        Path knowledge = knowledgeImage(root);
        Files.delete(knowledge.resolve("app/javaclaw-knowledge-worker-5.0.jar"));
        assertThrows(IllegalStateException.class, () -> WorkerImageLayout.discover(root, "knowledge"));
    }

    @Test
    void 主进程库发现Worker私有依赖时拒绝启动() throws Exception {
        Path root = distribution();
        Path library = root.resolve("lib");
        Files.createFile(library.resolve("playwright-1.52.0.jar"));

        assertThrows(IllegalStateException.class, () -> WorkerImageLayout.requireMainLibraryIsolation(library));
    }

    @Test
    void AppServer仅接收三类完整镜像的固定根() throws Exception {
        Path root = distribution();
        Path runtime = Files.createDirectories(root.resolve("runtime/bin"));
        Path java = runtime.resolve("java" + executableSuffix());
        executable(java);
        Path browser = browserImage(root).toRealPath();
        Path knowledge = knowledgeImage(root).toRealPath();
        Path skill = skillImage(root).toRealPath();
        RuntimeLayout layout = new RuntimeLayout(root, java, root.resolve("lib"), Optional.empty());

        List<String> properties = layout.appServerProperties();

        assertTrue(properties.contains("-Djavaclaw.browser.worker.image-root=" + browser));
        assertTrue(properties.contains("-Djavaclaw.knowledge.worker.image-root=" + knowledge));
        assertTrue(properties.contains("-Djavaclaw.skill.worker.image-root=" + skill));
    }

    @Test
    void 未知类型和符号链接镜像根被拒绝() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> WorkerImageLayout.discover(distribution(), "mail"));
        Assumptions.assumeFalse(RuntimeLayout.isWindows());
        Path root = distribution();
        Path image = browserImage(root);
        Path relocated = temporaryDirectory.resolve("relocated-browser");
        Files.move(image, relocated);
        Files.createSymbolicLink(image, relocated);

        assertThrows(IllegalStateException.class, () -> WorkerImageLayout.discover(root, "browser"));
    }

    @Test
    void Browser无Chromium以及Knowledge缺依赖或混入跨进程依赖时拒绝() throws Exception {
        Path browserRoot = Files.createDirectories(temporaryDirectory.resolve("browser-root"));
        Files.createDirectories(browserRoot.resolve("lib"));
        Path browser = browserImage(browserRoot);
        Files.delete(browser.resolve("browser/chromium-1169/chrome" + executableSuffix()));
        assertThrows(IllegalStateException.class, () -> WorkerImageLayout.discover(browserRoot, "browser"));

        Path missingRoot = Files.createDirectories(temporaryDirectory.resolve("missing-root"));
        Files.createDirectories(missingRoot.resolve("lib"));
        Path missing = knowledgeImage(missingRoot);
        Files.delete(missing.resolve("app/poi-ooxml-5.4.jar"));
        assertThrows(IllegalStateException.class, () -> WorkerImageLayout.discover(missingRoot, "knowledge"));

        Path foreignRoot = Files.createDirectories(temporaryDirectory.resolve("foreign-root"));
        Files.createDirectories(foreignRoot.resolve("lib"));
        Path foreign = knowledgeImage(foreignRoot);
        Files.createFile(foreign.resolve("app/playwright-1.52.0.jar"));
        assertThrows(IllegalStateException.class, () -> WorkerImageLayout.discover(foreignRoot, "knowledge"));
    }

    @Test
    void 不安全标记和不可执行工具无法形成Worker能力() throws Exception {
        Assumptions.assumeFalse(RuntimeLayout.isWindows());
        Path markerRoot = Files.createDirectories(temporaryDirectory.resolve("marker-root"));
        Files.createDirectories(markerRoot.resolve("lib"));
        Path knowledge = knowledgeImage(markerRoot);
        Path marker = knowledge.resolve("worker-image-v1.capability");
        Files.delete(marker);
        Files.createDirectory(marker);
        assertThrows(IllegalStateException.class, () -> WorkerImageLayout.discover(markerRoot, "knowledge"));

        Path executableRoot = Files.createDirectories(temporaryDirectory.resolve("executable-root"));
        Files.createDirectories(executableRoot.resolve("lib"));
        Path skill = skillImage(executableRoot);
        Files.setPosixFilePermissions(
                skill.resolve("bin/jshell"), Set.of(java.nio.file.attribute.PosixFilePermission.OWNER_READ));
        assertThrows(IllegalStateException.class, () -> WorkerImageLayout.discover(executableRoot, "skill"));
    }

    private Path distribution() throws IOException {
        Path root = Files.createDirectories(temporaryDirectory.resolve("distribution"));
        Files.createDirectories(root.resolve("lib"));
        return root;
    }

    private Path browserImage(Path root) throws IOException {
        Path image = image(root, "browser");
        Files.createDirectories(image.resolve("app"));
        Files.createFile(image.resolve("app/javaclaw-browser-service-5.0.jar"));
        Files.createFile(image.resolve("app/playwright-1.52.0.jar"));
        Path browser = Files.createDirectories(image.resolve("browser/chromium-1169"));
        executable(browser.resolve("chrome" + executableSuffix()));
        Files.writeString(
                image.resolve("browser/.javaclaw-playwright-version"), "playwright:1.52.0", StandardCharsets.US_ASCII);
        return image;
    }

    private Path knowledgeImage(Path root) throws IOException {
        Path image = image(root, "knowledge");
        Files.createDirectories(image.resolve("app"));
        Files.createFile(image.resolve("app/javaclaw-knowledge-worker-5.0.jar"));
        Files.createFile(image.resolve("app/pdfbox-3.0.jar"));
        Files.createFile(image.resolve("app/poi-ooxml-5.4.jar"));
        return image;
    }

    private Path skillImage(Path root) throws IOException {
        Path image = image(root, "skill");
        executable(image.resolve("bin/jshell" + executableSuffix()));
        return image;
    }

    private Path image(Path root, String type) throws IOException {
        Path image = Files.createDirectories(root.resolve("workers").resolve(type));
        executable(image.resolve("bin/java" + executableSuffix()));
        Files.writeString(image.resolve("worker-image-v1.capability"), "worker-image-v1:" + type);
        return image;
    }

    private static void executable(Path path) throws IOException {
        Files.createDirectories(path.getParent());
        Files.writeString(path, "runtime");
        if (!path.toFile().setExecutable(true) && !Files.isExecutable(path)) {
            throw new IOException("cannot make test executable: " + path);
        }
    }

    private static String executableSuffix() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("windows") ? ".exe" : "";
    }
}
