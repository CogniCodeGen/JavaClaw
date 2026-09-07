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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkerImageAssemblerMainTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void Browser来源版本和独立依赖完整时才生成只读镜像标记() throws Exception {
        Path source = browserSource("1.52.0");
        Path image = browserImage();

        WorkerImageAssemblerMain.copyBrowser(source, image, "1.52.0");

        Path marker = image.resolve(WorkerImageAssemblerMain.IMAGE_MARKER);
        assertEquals(
                "worker-image-v1:browser",
                Files.readString(marker, StandardCharsets.US_ASCII).strip());
        assertTrue(BrowserNativeCapability.isReadOnly(marker));
        assertTrue(Files.isRegularFile(image.resolve("browser/chromium-1169/chrome" + executableSuffix())));
    }

    @Test
    void Browser来源版本不符时失败关闭且不发布标记() throws Exception {
        Path source = browserSource("1.51.0");
        Path image = browserImage();

        assertThrows(IOException.class, () -> WorkerImageAssemblerMain.copyBrowser(source, image, "1.52.0"));

        assertFalse(Files.exists(image.resolve(WorkerImageAssemblerMain.IMAGE_MARKER)));
        try (var entries = Files.list(image.resolve("browser"))) {
            assertTrue(entries.findAny().isEmpty());
        }
    }

    @Test
    void Browser来源中的越界符号链接被拒绝() throws Exception {
        Assumptions.assumeFalse(isWindows());
        Path source = browserSource("1.52.0");
        Path image = browserImage();
        Files.createSymbolicLink(source.resolve("escape"), temporaryDirectory.resolve("outside"));

        assertThrows(IOException.class, () -> WorkerImageAssemblerMain.copyBrowser(source, image, "1.52.0"));
        assertFalse(Files.exists(image.resolve(WorkerImageAssemblerMain.IMAGE_MARKER)));
    }

    @Test
    void Knowledge和Skill仅在固定入口与工具链完整时发布标记() throws Exception {
        Path knowledge = runtimeImage("knowledge");
        Files.createDirectories(knowledge.resolve("app"));
        Files.createFile(knowledge.resolve("app/javaclaw-knowledge-worker-6.0.jar"));
        Files.createFile(knowledge.resolve("app/pdfbox-3.0.jar"));
        Files.createFile(knowledge.resolve("app/poi-ooxml-5.4.jar"));
        Path skill = runtimeImage("skill");
        executable(skill.resolve("bin/jshell" + executableSuffix()));

        WorkerImageAssemblerMain.mark(knowledge, "knowledge");
        WorkerImageAssemblerMain.mark(skill, "skill");

        assertEquals(
                "worker-image-v1:knowledge",
                Files.readString(knowledge.resolve(WorkerImageAssemblerMain.IMAGE_MARKER))
                        .strip());
        assertEquals(
                "worker-image-v1:skill",
                Files.readString(skill.resolve(WorkerImageAssemblerMain.IMAGE_MARKER))
                        .strip());
    }

    private Path browserSource(String version) throws IOException {
        Path source = Files.createDirectories(temporaryDirectory.resolve("source-" + version));
        Files.writeString(
                source.resolve(WorkerImageAssemblerMain.PLAYWRIGHT_MARKER),
                "playwright:" + version + System.lineSeparator(),
                StandardCharsets.US_ASCII);
        executable(source.resolve("chromium-1169/chrome" + executableSuffix()));
        return source;
    }

    private Path browserImage() throws IOException {
        Path image = runtimeImage("browser");
        Files.createDirectories(image.resolve("app"));
        Files.createFile(image.resolve("app/javaclaw-browser-service-6.0.jar"));
        Files.createFile(image.resolve("app/playwright-1.52.0.jar"));
        Files.createDirectories(image.resolve("browser"));
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
