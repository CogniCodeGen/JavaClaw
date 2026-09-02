package com.javaclaw.launcher;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import com.javaclaw.nativehost.transport.WindowsNamedPipeRpcServer;
import com.javaclaw.nativehost.transport.WindowsPipeName;
import com.javaclaw.protocol.JsonRpcCodec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

@EnabledOnOs(OS.WINDOWS)
class WindowsServerSupervisorTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void reusesReachableCurrentUserPipeWithoutStartingProcess() throws Exception {
        WindowsPipeName name = WindowsPipeName.currentUserDefault();
        AtomicReference<Throwable> serverFailure = new AtomicReference<>();
        try (WindowsNamedPipeRpcServer server = WindowsNamedPipeRpcServer.bind(name)) {
            Thread accepting = Thread.ofVirtual().start(() -> {
                try (var ignored = server.accept(new JsonRpcCodec())) {
                    // Supervisor probe closes immediately after connecting.
                } catch (Throwable failure) {
                    serverFailure.set(failure);
                }
            });

            WindowsPipeName actual = new WindowsServerSupervisor(layout()).ensureRunning();
            accepting.join(Duration.ofSeconds(2));

            assertEquals(name, actual);
            assertNull(serverFailure.get());
        }
    }

    private RuntimeLayout layout() throws Exception {
        Path root = temporaryDirectory.resolve("distribution").toAbsolutePath();
        Path library = Files.createDirectories(root.resolve("lib"));
        Path bin = Files.createDirectories(root.resolve("bin"));
        Path launcher = Files.createFile(bin.resolve("javaclaw-service.cmd"));
        Path java = Path.of(System.getProperty("java.home"), "bin", "java.exe").toAbsolutePath();
        return new RuntimeLayout(root, java, library, Optional.of(launcher));
    }
}
