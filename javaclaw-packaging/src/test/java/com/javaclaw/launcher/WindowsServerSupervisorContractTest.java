package com.javaclaw.launcher;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.javaclaw.protocol.TransportKind;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class WindowsServerSupervisorContractTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void 非Windows平台保留NamedPipe契约但立即失败关闭() throws Exception {
        Assumptions.assumeFalse(RuntimeLayout.isWindows());
        Path root = Files.createDirectories(temporaryDirectory.resolve("distribution"));
        Path java = Files.createDirectories(root.resolve("runtime/bin")).resolve("java.exe");
        Files.createFile(java);
        Path library = Files.createDirectories(root.resolve("lib"));
        WindowsServerSupervisor supervisor =
                new WindowsServerSupervisor(new RuntimeLayout(root, java, library, Optional.empty()));

        assertFalse(supervisor.running());
        assertEquals(TransportKind.NAMED_PIPE, supervisor.transport().kind());
        assertThrows(java.io.IOException.class, supervisor::ensureRunning);
        assertThrows(java.io.IOException.class, supervisor::start);
    }
}
