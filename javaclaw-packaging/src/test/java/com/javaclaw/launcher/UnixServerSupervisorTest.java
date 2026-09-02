package com.javaclaw.launcher;

import java.io.IOException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.ServerSocketChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UnixServerSupervisorTest {
    @TempDir
    Path temporaryDirectory;

    private final String originalHome = System.getProperty("user.home");
    private Path shortHome;

    @AfterEach
    void 恢复用户目录() throws IOException {
        if (originalHome == null) {
            System.clearProperty("user.home");
        } else {
            System.setProperty("user.home", originalHome);
        }
        deleteShortHome();
    }

    @Test
    void 已可连接的服务直接复用固定Socket() throws Exception {
        Assumptions.assumeFalse(RuntimeLayout.isWindows());
        Path socket = configureShortHome();
        RuntimeLayout layout = createLayout(realJava());

        try (ServerSocketChannel server = ServerSocketChannel.open(StandardProtocolFamily.UNIX)) {
            server.bind(UnixDomainSocketAddress.of(socket));
            assertEquals(socket, new UnixServerSupervisor(layout).ensureRunning());
        }
    }

    @Test
    void 损坏Socket和提前退出的服务进程给出日志位置() throws Exception {
        Assumptions.assumeFalse(RuntimeLayout.isWindows());
        Path socket = configureShortHome();
        Files.writeString(socket, "not a socket");
        RuntimeLayout layout = createLayout(realJava());

        IOException failure = assertThrows(IOException.class, () -> new UnixServerSupervisor(layout).ensureRunning());

        Path log = shortHome.resolve(".javaclaw/data-v5/logs/app-server.log");
        assertEquals("App Server 启动失败，请检查日志: " + log, failure.getMessage());
        assertTrue(Files.isRegularFile(log));
        assertTrue(Files.readString(log).contains("com.javaclaw.server.AppServerMain"));
    }

    private Path configureShortHome() throws IOException {
        shortHome = Files.createTempDirectory(Path.of("/tmp"), "jcs");
        System.setProperty("user.home", shortHome.toString());
        Path socket = shortHome.resolve(".javaclaw/run/app-server-v5.sock");
        Files.createDirectories(socket.getParent());
        return socket;
    }

    private RuntimeLayout createLayout(Path java) throws IOException {
        Path root = temporaryDirectory.resolve("distribution").toAbsolutePath();
        Path library = Files.createDirectories(root.resolve("lib"));
        Path bin = Files.createDirectories(root.resolve("bin"));
        Path launcher = Files.createFile(bin.resolve("javaclaw-service"));
        return new RuntimeLayout(root, java, library, Optional.of(launcher));
    }

    private static Path realJava() {
        return Path.of(System.getProperty("java.home"), "bin", "java").toAbsolutePath();
    }

    private void deleteShortHome() throws IOException {
        if (shortHome == null || Files.notExists(shortHome)) {
            return;
        }
        try (java.util.stream.Stream<Path> paths = Files.walk(shortHome)) {
            for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }
}
