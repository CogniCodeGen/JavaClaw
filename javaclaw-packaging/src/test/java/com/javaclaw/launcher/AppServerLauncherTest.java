package com.javaclaw.launcher;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AppServerLauncherTest {
    private static final String PROGRAM_DIRECTORY_PROPERTY = "javaclaw.program.dir";
    private static final List<String> PROPERTIES =
            List.of("javaclaw.data.root", "javaclaw.log.dir", "javaclaw.log.process");

    @TempDir
    Path temporaryDirectory;

    private final String originalProgramDirectory = System.getProperty(PROGRAM_DIRECTORY_PROPERTY);
    private final Map<String, String> originalProperties = new HashMap<>();

    @BeforeEach
    void 隔离运行属性() {
        for (String name : PROPERTIES) {
            originalProperties.put(name, System.getProperty(name));
            System.clearProperty(name);
        }
    }

    @AfterEach
    void 清理测试属性() {
        originalProperties.forEach((name, value) -> {
            if (value == null) {
                System.clearProperty(name);
            } else {
                System.setProperty(name, value);
            }
        });
        if (originalProgramDirectory == null) {
            System.clearProperty(PROGRAM_DIRECTORY_PROPERTY);
        } else {
            System.setProperty(PROGRAM_DIRECTORY_PROPERTY, originalProgramDirectory);
        }
    }

    @Test
    void 命令仅传播允许的运行属性且不猜测Worker路径() throws Exception {
        RuntimeLayout layout = layout();
        System.setProperty(
                "javaclaw.data.root", temporaryDirectory.resolve("data-v6").toString());
        System.setProperty("javaclaw.log.process", "test-server");

        List<String> command = AppServerLauncher.command(layout, new String[] {"--stdio"});

        assertEquals(layout.javaExecutable().toString(), command.getFirst());
        assertTrue(command.contains("-Djavaclaw.data.root=" + temporaryDirectory.resolve("data-v6")));
        assertTrue(command.contains("-Djavaclaw.log.process=test-server"));
        assertTrue(command.contains("com.javaclaw.server.AppServerMain"));
        assertTrue(command.contains("-Djavaclaw.program.dir=" + layout.root()));
        assertEquals("--stdio", command.getLast());
        assertFalse(command.stream().anyMatch(argument -> argument.contains("worker.image-root")));
    }

    @Test
    void 主入口等待Server并准确报告非零退出码() throws Exception {
        Assumptions.assumeFalse(RuntimeLayout.isWindows());
        Path successful = distribution("successful", 0);
        System.setProperty(PROGRAM_DIRECTORY_PROPERTY, successful.toString());

        AppServerLauncher.main(new String[] {"--stdio"});

        Path failing = distribution("failing", 19);
        System.setProperty(PROGRAM_DIRECTORY_PROPERTY, failing.toString());
        IllegalStateException failure =
                assertThrows(IllegalStateException.class, () -> AppServerLauncher.main(new String[] {"--stdio"}));
        assertEquals("JavaClaw App Server exited with 19", failure.getMessage());
    }

    private RuntimeLayout layout() throws IOException {
        Path root = Files.createDirectories(temporaryDirectory.resolve("distribution"));
        Path runtime = Files.createDirectories(root.resolve("runtime/bin"));
        Path library = Files.createDirectories(root.resolve("lib"));
        Path java = Files.writeString(runtime.resolve(RuntimeLayout.isWindows() ? "java.exe" : "java"), "runtime");
        return new RuntimeLayout(root, java, library, Optional.empty());
    }

    private Path distribution(String name, int exitCode) throws IOException {
        Path root = temporaryDirectory.resolve(name).toAbsolutePath();
        Path runtime = Files.createDirectories(root.resolve("runtime/bin"));
        Files.createDirectories(root.resolve("lib"));
        Path bin = Files.createDirectories(root.resolve("bin"));
        Path java = Files.writeString(
                runtime.resolve("java"), "#!/bin/sh\nexit " + exitCode + "\n", StandardCharsets.UTF_8);
        Files.setPosixFilePermissions(
                java,
                Set.of(
                        PosixFilePermission.OWNER_READ,
                        PosixFilePermission.OWNER_WRITE,
                        PosixFilePermission.OWNER_EXECUTE));
        Files.createFile(bin.resolve("javaclaw-service"));
        return root;
    }
}
