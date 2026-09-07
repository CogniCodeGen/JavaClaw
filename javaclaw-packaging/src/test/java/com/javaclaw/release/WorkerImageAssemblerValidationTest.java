package com.javaclaw.release;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkerImageAssemblerValidationTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void 命令行入口只接受Browser复制和固定类型标记() throws Exception {
        Path source = browserSource("command-source", "1.52.0", true);
        Path browser = browserImage("command-browser");
        Path skill = runtimeImage("command-skill");
        executable(skill.resolve("bin/jshell" + executableSuffix()));

        WorkerImageAssemblerMain.main(new String[] {"browser", source.toString(), browser.toString(), "1.52.0"});
        WorkerImageAssemblerMain.main(new String[] {"mark", skill.toString(), " SKILL "});

        assertTrue(Files.isRegularFile(browser.resolve(WorkerImageAssemblerMain.IMAGE_MARKER)));
        assertEquals(
                "worker-image-v1:skill",
                Files.readString(skill.resolve(WorkerImageAssemblerMain.IMAGE_MARKER))
                        .strip());
        assertThrows(IllegalArgumentException.class, () -> WorkerImageAssemblerMain.main(new String[0]));
        assertThrows(NullPointerException.class, () -> WorkerImageAssemblerMain.main(null));
    }

    @Test
    void Browser来源必须具有安全版本标记和Chromium() throws Exception {
        Path missingMarker = Files.createDirectories(temporaryDirectory.resolve("missing-marker"));
        executable(missingMarker.resolve("chrome" + executableSuffix()));
        Path missingChromium = browserSource("missing-chromium", "1.52.0", false);

        assertThrows(
                IOException.class,
                () -> WorkerImageAssemblerMain.copyBrowser(missingMarker, browserImage("marker-target"), "1.52.0"));
        assertThrows(
                IOException.class,
                () -> WorkerImageAssemblerMain.copyBrowser(missingChromium, browserImage("chromium-target"), "1.52.0"));
    }

    @Test
    void Browser版本只接受有界的数字点分格式() throws Exception {
        Path source = Files.createDirectories(temporaryDirectory.resolve("invalid-version-source"));
        Path image = browserImage("invalid-version-image");

        assertThrows(IllegalArgumentException.class, () -> WorkerImageAssemblerMain.copyBrowser(source, image, ""));
        assertThrows(IllegalArgumentException.class, () -> WorkerImageAssemblerMain.copyBrowser(source, image, "1"));
        assertThrows(
                IllegalArgumentException.class, () -> WorkerImageAssemblerMain.copyBrowser(source, image, "1.2.3.4.5"));
        assertThrows(NullPointerException.class, () -> WorkerImageAssemblerMain.copyBrowser(source, image, null));
    }

    @Test
    void Browser目标必须为空目录且与来源物理隔离() throws Exception {
        Path source = browserSource("safe-source", "1.52.0", true);
        Path nonEmpty = browserImage("non-empty-image");
        Files.writeString(nonEmpty.resolve("browser/old-runtime"), "old");
        Path unsafe = runtimeImage("unsafe-image");
        Files.writeString(unsafe.resolve("browser"), "not-a-directory");
        Path nestedSource = browserSource(unsafe.resolve("nested-source"), "1.52.0", true);

        assertThrows(IOException.class, () -> WorkerImageAssemblerMain.copyBrowser(source, nonEmpty, "1.52.0"));
        assertThrows(IOException.class, () -> WorkerImageAssemblerMain.copyBrowser(source, unsafe, "1.52.0"));
        assertThrows(IOException.class, () -> WorkerImageAssemblerMain.copyBrowser(nestedSource, unsafe, "1.52.0"));
    }

    @Test
    void Worker类型和启动工具缺失时失败关闭() throws Exception {
        Path empty = Files.createDirectories(temporaryDirectory.resolve("empty-image"));
        Path skill = runtimeImage("skill-without-jshell");

        assertThrows(IllegalArgumentException.class, () -> WorkerImageAssemblerMain.mark(empty, "mail"));
        assertThrows(NullPointerException.class, () -> WorkerImageAssemblerMain.mark(empty, null));
        assertThrows(IOException.class, () -> WorkerImageAssemblerMain.mark(empty, "knowledge"));
        assertThrows(IOException.class, () -> WorkerImageAssemblerMain.mark(skill, "skill"));
    }

    @Test
    void Knowledge镜像拒绝缺失入口依赖和跨进程依赖() throws Exception {
        assertThrows(IOException.class, () -> WorkerImageAssemblerMain.mark(knowledgeImage("entry"), "knowledge"));
        assertThrows(IOException.class, () -> WorkerImageAssemblerMain.mark(knowledgeImage("pdf"), "knowledge"));
        assertThrows(IOException.class, () -> WorkerImageAssemblerMain.mark(knowledgeImage("poi"), "knowledge"));
        assertThrows(IOException.class, () -> WorkerImageAssemblerMain.mark(knowledgeImage("foreign"), "knowledge"));
    }

    @Test
    void Browser镜像拒绝缺失入口依赖跨进程依赖和Chromium() throws Exception {
        assertThrows(IOException.class, () -> WorkerImageAssemblerMain.mark(browserLayout("entry"), "browser"));
        assertThrows(IOException.class, () -> WorkerImageAssemblerMain.mark(browserLayout("playwright"), "browser"));
        assertThrows(IOException.class, () -> WorkerImageAssemblerMain.mark(browserLayout("foreign"), "browser"));
        assertThrows(IOException.class, () -> WorkerImageAssemblerMain.mark(browserLayout("chromium"), "browser"));
    }

    @Test
    void 已有标记可原子改写但目录或符号链接标记被拒绝() throws Exception {
        Path skill = runtimeImage("rewrite-skill");
        executable(skill.resolve("bin/jshell" + executableSuffix()));
        WorkerImageAssemblerMain.mark(skill, "skill");
        WorkerImageAssemblerMain.mark(skill, "skill");
        assertEquals(
                "worker-image-v1:skill",
                Files.readString(skill.resolve(WorkerImageAssemblerMain.IMAGE_MARKER))
                        .strip());

        Path directoryMarker = runtimeImage("directory-marker");
        executable(directoryMarker.resolve("bin/jshell" + executableSuffix()));
        Files.createDirectory(directoryMarker.resolve(WorkerImageAssemblerMain.IMAGE_MARKER));
        assertThrows(IOException.class, () -> WorkerImageAssemblerMain.mark(directoryMarker, "skill"));
    }

    @Test
    void 来源内相对符号链接被原样复制而越界链接被拒绝() throws Exception {
        Assumptions.assumeFalse(isWindows());
        Path source = browserSource("linked-source", "1.52.0", true);
        Files.writeString(source.resolve("payload"), "payload");
        Files.createSymbolicLink(source.resolve("alias"), Path.of("payload"));
        Path image = browserImage("linked-image");

        WorkerImageAssemblerMain.copyBrowser(source, image, "1.52.0");

        assertTrue(Files.isSymbolicLink(image.resolve("browser/alias")));
        assertEquals(Path.of("payload"), Files.readSymbolicLink(image.resolve("browser/alias")));
    }

    private Path browserSource(String name, String version, boolean chromium) throws IOException {
        return browserSource(temporaryDirectory.resolve(name), version, chromium);
    }

    private Path browserSource(Path source, String version, boolean chromium) throws IOException {
        Files.createDirectories(source);
        Files.writeString(
                source.resolve(WorkerImageAssemblerMain.PLAYWRIGHT_MARKER),
                "playwright:" + version,
                StandardCharsets.US_ASCII);
        if (chromium) {
            executable(source.resolve("chromium/chrome" + executableSuffix()));
        }
        return source;
    }

    private Path browserImage(String name) throws IOException {
        Path image = runtimeImage(name);
        Path app = Files.createDirectories(image.resolve("app"));
        Files.createFile(app.resolve("javaclaw-browser-service-6.0.jar"));
        Files.createFile(app.resolve("playwright-1.52.0.jar"));
        Files.createDirectory(image.resolve("browser"));
        return image;
    }

    private Path knowledgeImage(String failure) throws IOException {
        Path image = runtimeImage("knowledge-" + failure);
        Path app = Files.createDirectories(image.resolve("app"));
        if (!"entry".equals(failure)) {
            Files.createFile(app.resolve("javaclaw-knowledge-worker-6.0.jar"));
        }
        if (!"entry".equals(failure) && !"pdf".equals(failure)) {
            Files.createFile(app.resolve("pdfbox-3.0.jar"));
        }
        if ("foreign".equals(failure)) {
            Files.createFile(app.resolve("poi-ooxml-5.4.jar"));
            Files.createFile(app.resolve("playwright-1.52.0.jar"));
        }
        return image;
    }

    private Path browserLayout(String failure) throws IOException {
        Path image = runtimeImage("browser-" + failure);
        Path app = Files.createDirectories(image.resolve("app"));
        if (!"entry".equals(failure)) {
            Files.createFile(app.resolve("javaclaw-browser-service-6.0.jar"));
        }
        if (!"entry".equals(failure) && !"playwright".equals(failure)) {
            Files.createFile(app.resolve("playwright-1.52.0.jar"));
        }
        if ("foreign".equals(failure)) {
            Files.createFile(app.resolve("pdfbox-3.0.jar"));
        }
        Path browser = Files.createDirectories(image.resolve("browser"));
        if (!"chromium".equals(failure)) {
            executable(browser.resolve("chrome" + executableSuffix()));
        }
        return image;
    }

    private Path runtimeImage(String name) throws IOException {
        Path image = Files.createDirectories(temporaryDirectory.resolve(name));
        executable(image.resolve("bin/java" + executableSuffix()));
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
        return isWindows() ? ".exe" : "";
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("windows");
    }
}
