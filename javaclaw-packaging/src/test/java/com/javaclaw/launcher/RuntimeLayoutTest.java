package com.javaclaw.launcher;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuntimeLayoutTest {
    private static final String PROGRAM_DIRECTORY_PROPERTY = "javaclaw.program.dir";

    @TempDir
    Path temporaryDirectory;

    private final String originalProgramDirectory = System.getProperty(PROGRAM_DIRECTORY_PROPERTY);
    private final String originalOperatingSystem = System.getProperty("os.name");

    @AfterEach
    void 恢复系统属性() {
        restore(PROGRAM_DIRECTORY_PROPERTY, originalProgramDirectory);
        restore("os.name", originalOperatingSystem);
    }

    @Test
    void 有效布局提供发行包Classpath() throws Exception {
        RuntimeLayout layout = createLayout("unix", false);

        assertEquals(temporaryDirectory.resolve("unix").toRealPath(), layout.root());
        assertEquals(layout.libraryDirectory().resolve("*").toString(), layout.classpath());
        assertTrue(Files.isRegularFile(layout.javaExecutable()));
        assertTrue(Files.isRegularFile(layout.serviceLauncher().orElseThrow()));
    }

    @Test
    void 布局拒绝空值相对路径和缺失资源() throws Exception {
        RuntimeLayout valid = createLayout("valid", false);

        assertThrows(
                NullPointerException.class,
                () -> new RuntimeLayout(
                        null, valid.javaExecutable(), valid.libraryDirectory(), valid.serviceLauncher()));
        assertThrows(
                IllegalArgumentException.class,
                () -> new RuntimeLayout(
                        Path.of("relative"),
                        valid.javaExecutable(),
                        valid.libraryDirectory(),
                        valid.serviceLauncher()));
        assertThrows(
                IllegalStateException.class,
                () -> new RuntimeLayout(
                        valid.root(),
                        valid.root().resolve("missing-java"),
                        valid.libraryDirectory(),
                        valid.serviceLauncher()));
        assertThrows(
                IllegalStateException.class,
                () -> new RuntimeLayout(
                        valid.root(),
                        valid.javaExecutable(),
                        valid.root().resolve("missing-lib"),
                        valid.serviceLauncher()));
        assertThrows(
                IllegalStateException.class,
                () -> new RuntimeLayout(
                        valid.root(),
                        valid.javaExecutable(),
                        valid.libraryDirectory(),
                        Optional.of(valid.root().resolve("missing-launcher"))));
    }

    @Test
    void 系统属性按平台解析发行布局() throws Exception {
        RuntimeLayout unix = createLayout("system-unix", false);
        System.setProperty("os.name", "Linux");
        System.setProperty(PROGRAM_DIRECTORY_PROPERTY, "  " + unix.root() + "  ");

        assertEquals(unix, RuntimeLayout.fromSystemProperties());
        assertFalse(RuntimeLayout.isWindows());

        RuntimeLayout windows = createLayout("system-windows", true);
        System.setProperty("os.name", "Windows 11");
        System.setProperty(PROGRAM_DIRECTORY_PROPERTY, windows.root().toString());

        assertEquals(windows, RuntimeLayout.fromSystemProperties());
        assertTrue(RuntimeLayout.isWindows());
    }

    @Test
    void 未配置发行目录时立即拒绝启动() {
        System.clearProperty(PROGRAM_DIRECTORY_PROPERTY);
        assertThrows(IllegalStateException.class, RuntimeLayout::fromSystemProperties);

        System.setProperty(PROGRAM_DIRECTORY_PROPERTY, "   ");
        assertThrows(IllegalStateException.class, RuntimeLayout::fromSystemProperties);
    }

    @Test
    void 单目录Classpath布局使用宿主Runtime且启动脚本可缺省() throws Exception {
        Path root = Files.createDirectories(temporaryDirectory.resolve("flat-layout"));
        System.setProperty("os.name", "Linux");
        System.setProperty(PROGRAM_DIRECTORY_PROPERTY, root.toString());

        RuntimeLayout layout = RuntimeLayout.fromSystemProperties();

        assertEquals(root.toRealPath(), layout.libraryDirectory());
        assertEquals(Path.of(System.getProperty("java.home"), "bin", "java").toRealPath(), layout.javaExecutable());
        assertTrue(layout.serviceLauncher().isEmpty());
    }

    @Test
    void 库目录或启动脚本逃出发行根时拒绝布局() throws Exception {
        RuntimeLayout valid = createLayout("inside", false);
        Path outsideLibrary = Files.createDirectories(temporaryDirectory.resolve("outside-library"));
        Path outsideLauncher = Files.createFile(temporaryDirectory.resolve("outside-launcher"));

        assertThrows(
                IllegalStateException.class,
                () -> new RuntimeLayout(valid.root(), valid.javaExecutable(), outsideLibrary, valid.serviceLauncher()));
        assertThrows(
                IllegalStateException.class,
                () -> new RuntimeLayout(
                        valid.root(), valid.javaExecutable(), valid.libraryDirectory(), Optional.of(outsideLauncher)));
    }

    private RuntimeLayout createLayout(String name, boolean windows) throws IOException {
        Path root = temporaryDirectory.resolve(name).toAbsolutePath();
        Path runtime = Files.createDirectories(root.resolve("runtime/bin"));
        Path library = Files.createDirectories(root.resolve("lib"));
        Path bin = Files.createDirectories(root.resolve("bin"));
        Path java = Files.createFile(runtime.resolve(windows ? "java.exe" : "java"));
        Path launcher = Files.createFile(bin.resolve(windows ? "javaclaw-service.cmd" : "javaclaw-service"));
        return new RuntimeLayout(root, java, library, Optional.of(launcher));
    }

    private static void restore(String name, String value) {
        if (value == null) {
            System.clearProperty(name);
        } else {
            System.setProperty(name, value);
        }
    }
}
