package com.javaclaw.launcher;

import java.io.IOException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.ServerSocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JavaClawLauncherTest {
    private static final String PROGRAM_DIRECTORY_PROPERTY = "javaclaw.program.dir";

    @TempDir
    Path temporaryDirectory;

    private final String originalHome = System.getProperty("user.home");
    private final String originalOperatingSystem = System.getProperty("os.name");
    private final String originalProgramDirectory = System.getProperty(PROGRAM_DIRECTORY_PROPERTY);
    private Path shortHome;

    @AfterEach
    void 恢复进程环境() throws IOException {
        restore("user.home", originalHome);
        restore("os.name", originalOperatingSystem);
        restore(PROGRAM_DIRECTORY_PROPERTY, originalProgramDirectory);
        deleteShortHome();
    }

    @Test
    void 已运行服务允许Desktop正常退出并报告失败退出码() throws Exception {
        Assumptions.assumeFalse(RuntimeLayout.isWindows());
        System.setProperty("os.name", "Mac OS X");
        Path socket = configureShortHome();
        try (ServerSocketChannel server = listen(socket)) {
            Path successful = createDistribution("successful", 0, false);
            System.setProperty(PROGRAM_DIRECTORY_PROPERTY, successful.toString());
            assertDoesNotThrow(() -> JavaClawLauncher.main(new String[] {"--profile", "test"}));

            Path failing = createDistribution("failing", 23, false);
            System.setProperty(PROGRAM_DIRECTORY_PROPERTY, failing.toString());
            IllegalStateException failure =
                    assertThrows(IllegalStateException.class, () -> JavaClawLauncher.main(new String[0]));
            assertEquals("JavaClaw Desktop exited with 23", failure.getMessage());
        }
    }

    @Test
    void WindowsDesktop命令使用NamedPipe并启用FFM() throws Exception {
        Path distribution = createDistribution("windows", 0, true);
        System.setProperty("os.name", "Windows 11");
        System.setProperty(PROGRAM_DIRECTORY_PROPERTY, distribution.toString());

        RuntimeLayout layout = RuntimeLayout.fromSystemProperties();
        List<String> command = JavaClawLauncher.desktopCommand(
                layout, "-Djavaclaw.server.pipe=javaclaw-test", new String[] {"--profile", "test"}, true);

        assertEquals(layout.javaExecutable().toString(), command.getFirst());
        assertTrue(command.contains("--enable-native-access=ALL-UNNAMED"));
        assertTrue(command.contains("-Djavaclaw.server.pipe=javaclaw-test"));
        assertTrue(command.contains("-Djavaclaw.launcher.supervised=true"));
        assertTrue(command.contains("-Djavaclaw.launcher.tray-active=true"));
        assertEquals(List.of("--profile", "test"), command.subList(command.size() - 2, command.size()));
    }

    private Path configureShortHome() throws IOException {
        shortHome = Files.createTempDirectory(Path.of("/tmp"), "jcl");
        System.setProperty("user.home", shortHome.toString());
        Path socket = shortHome.resolve(".javaclaw/run/app-server-v5.sock");
        Files.createDirectories(socket.getParent());
        return socket;
    }

    private Path createDistribution(String name, int exitCode, boolean windows) throws IOException {
        Path root = temporaryDirectory.resolve(name).toAbsolutePath();
        Path runtime = Files.createDirectories(root.resolve("runtime/bin"));
        Files.createDirectories(root.resolve("lib"));
        Path bin = Files.createDirectories(root.resolve("bin"));
        String javaName = windows ? "java.exe" : "java";
        Path java = Files.writeString(
                runtime.resolve(javaName), "#!/bin/sh\nexit " + exitCode + "\n", StandardCharsets.UTF_8);
        if (!windows) {
            Files.setPosixFilePermissions(
                    java,
                    Set.of(
                            PosixFilePermission.OWNER_READ,
                            PosixFilePermission.OWNER_WRITE,
                            PosixFilePermission.OWNER_EXECUTE));
        }
        Files.createFile(bin.resolve(windows ? "javaclaw-service.cmd" : "javaclaw-service"));
        return root;
    }

    private static ServerSocketChannel listen(Path socket) throws IOException {
        ServerSocketChannel server = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
        server.bind(UnixDomainSocketAddress.of(socket));
        return server;
    }

    private void deleteShortHome() throws IOException {
        if (shortHome == null || Files.notExists(shortHome)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(shortHome)) {
            for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    private static void restore(String name, String value) {
        if (value == null) {
            System.clearProperty(name);
        } else {
            System.setProperty(name, value);
        }
    }
}
