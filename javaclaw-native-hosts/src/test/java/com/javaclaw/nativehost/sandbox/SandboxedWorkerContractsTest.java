package com.javaclaw.nativehost.sandbox;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.javaclaw.api.ResourceLimits;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SandboxedWorkerContractsTest {
    private static final ResourceLimits LIMITS = new ResourceLimits(256L * 1024 * 1024, 4096, 2, 32);

    @TempDir
    Path temporaryDirectory;

    @Test
    void 描述会规范化路径复制集合并保留最小环境() throws Exception {
        Path executable = executable("/bin/cat", "/usr/bin/cat");
        Path executableRoot = executable.getParent();
        ArrayList<Path> readRoots = new ArrayList<>(List.of(executableRoot, executableRoot));
        ArrayList<Path> writeRoots = new ArrayList<>(List.of(temporaryDirectory));
        ArrayList<Path> executableRoots = new ArrayList<>(List.of(executableRoot, executableRoot));

        SandboxedWorkerCommand command = new SandboxedWorkerCommand(
                "  knowledge-worker  ",
                List.of(executable.toString()),
                temporaryDirectory.resolve("."),
                Map.of("LANG", "C"),
                readRoots,
                writeRoots,
                executableRoots,
                Duration.ofMinutes(1),
                LIMITS,
                Optional.empty());
        readRoots.clear();
        writeRoots.clear();
        executableRoots.clear();

        assertEquals("knowledge-worker", command.id());
        assertEquals(temporaryDirectory.toAbsolutePath().normalize(), command.workingDirectory());
        assertEquals(Map.of("LANG", "C"), command.environment());
        assertEquals(List.of(executableRoot), command.readRoots());
        assertEquals(List.of(temporaryDirectory.toAbsolutePath().normalize()), command.writeRoots());
        assertEquals(List.of(executableRoot), command.executableRoots());
    }

    @Test
    void 描述拒绝空标识空命令和无界路径集合() throws Exception {
        Path executable = executable("/bin/cat", "/usr/bin/cat");
        Path root = executable.getParent();

        assertThrows(
                NullPointerException.class,
                () -> command(null, List.of(executable.toString()), Map.of(), List.of(root), List.of(root)));
        assertInvalid(() -> command(" ", List.of(executable.toString()), Map.of(), List.of(root), List.of(root)));
        assertThrows(NullPointerException.class, () -> command("worker", null, Map.of(), List.of(root), List.of(root)));
        assertInvalid(() -> command("worker", List.of(), Map.of(), List.of(root), List.of(root)));
        assertInvalid(() -> command("worker", List.of(" "), Map.of(), List.of(root), List.of(root)));
        assertInvalid(() -> command("worker", List.of(executable.toString()), Map.of(), List.of(), List.of(root)));
        assertInvalid(() -> command("worker", List.of(executable.toString()), Map.of(), List.of(root), List.of()));
        assertInvalid(() -> new SandboxedWorkerCommand(
                "worker",
                List.of(executable.toString()),
                temporaryDirectory,
                Map.of(),
                List.of(root),
                List.of(),
                List.of(root),
                Duration.ofSeconds(1),
                LIMITS,
                Optional.empty()));
        assertThrows(
                NullPointerException.class,
                () -> new SandboxedWorkerCommand(
                        "worker",
                        List.of(executable.toString()),
                        temporaryDirectory,
                        Map.of(),
                        List.of(root),
                        List.of(temporaryDirectory),
                        java.util.Arrays.asList(root, null),
                        Duration.ofSeconds(1),
                        LIMITS,
                        Optional.empty()));
    }

    @Test
    void 描述拒绝宿主环境注入和含空字符的值() throws Exception {
        Path executable = executable("/bin/cat", "/usr/bin/cat");
        Path root = executable.getParent();
        for (String name : List.of(
                "1INVALID",
                "HOME",
                "home",
                "HTTP_PROXY",
                "https_proxy",
                "JAVA_TOOL_OPTIONS",
                "LD_PRELOAD",
                "DYLD_INSERT_LIBRARIES")) {
            assertThrows(
                    SecurityException.class,
                    () -> command(
                            "worker",
                            List.of(executable.toString()),
                            Map.of(name, "value"),
                            List.of(root),
                            List.of(root)));
        }
        assertThrows(
                SecurityException.class,
                () -> command(
                        "worker",
                        List.of(executable.toString()),
                        Map.of("SAFE", "before\0after"),
                        List.of(root),
                        List.of(root)));
        assertThrows(
                NullPointerException.class,
                () -> command(
                        "worker", List.of(executable.toString()), mapWithNullValue(), List.of(root), List.of(root)));
    }

    @Test
    void 描述拒绝超出边界的生命周期和缺失资源上限() throws Exception {
        Path executable = executable("/bin/cat", "/usr/bin/cat");
        Path root = executable.getParent();

        assertThrows(NullPointerException.class, () -> commandWithLifetime(executable, root, null, LIMITS));
        assertInvalid(() -> commandWithLifetime(executable, root, Duration.ofMillis(999), LIMITS));
        assertInvalid(() ->
                commandWithLifetime(executable, root, Duration.ofMinutes(10).plusMillis(1), LIMITS));
        assertThrows(
                NullPointerException.class, () -> commandWithLifetime(executable, root, Duration.ofSeconds(1), null));
    }

    @Test
    void 启动器在原生Backend前拒绝越权可执行根和缺失参数() throws Exception {
        Path executable = executable("/bin/cat", "/usr/bin/cat");
        Path executableRoot = executable.getParent();
        Path outsideRoot = Files.createDirectory(temporaryDirectory.resolve("outside"));
        SandboxedWorkerLauncher launcher = new SandboxedWorkerLauncher();
        SandboxedWorkerCommand outside = command(
                "outside", List.of(executable.toString()), Map.of(), List.of(executableRoot), List.of(outsideRoot));

        assertThrows(NullPointerException.class, () -> launcher.start(null));
        assertThrows(NullPointerException.class, () -> launcher.start(outside, null));
        assertThrows(SecurityException.class, () -> launcher.start(outside));
    }

    @Test
    void macOS原生Worker仅通过管道交换且调用方拥有进程() throws Exception {
        Assumptions.assumeTrue(isMacOs());
        Path executable = executable("/bin/cat", "/usr/bin/cat");
        Path executableRoot = executable.getParent();
        SandboxedWorkerCommand command = command(
                "pipe-owner",
                List.of(executable.toString()),
                Map.of("LANG", "C"),
                List.of(executableRoot),
                List.of(executableRoot));
        byte[] input = "worker-pipe".getBytes(StandardCharsets.UTF_8);

        assertWorkerEcho(command, SandboxErrorMode.DISCARD, input);
        assertWorkerEcho(command, SandboxErrorMode.MERGE_WITH_OUTPUT, input);
    }

    private SandboxedWorkerCommand command(
            String id,
            List<String> arguments,
            Map<String, String> environment,
            List<Path> readRoots,
            List<Path> executableRoots) {
        return new SandboxedWorkerCommand(
                id,
                arguments,
                temporaryDirectory,
                environment,
                readRoots,
                List.of(temporaryDirectory),
                executableRoots,
                Duration.ofSeconds(10),
                LIMITS,
                Optional.empty());
    }

    private SandboxedWorkerCommand commandWithLifetime(
            Path executable, Path root, Duration lifetime, ResourceLimits limits) {
        return new SandboxedWorkerCommand(
                "worker",
                List.of(executable.toString()),
                temporaryDirectory,
                Map.of(),
                List.of(root),
                List.of(temporaryDirectory),
                List.of(root),
                lifetime,
                limits,
                Optional.empty());
    }

    private static void assertWorkerEcho(SandboxedWorkerCommand command, SandboxErrorMode errorMode, byte[] input)
            throws Exception {
        Process process = new SandboxedWorkerLauncher().start(command, errorMode);
        try {
            process.getOutputStream().write(input);
            process.getOutputStream().close();
            assertArrayEquals(input, process.getInputStream().readAllBytes());
            assertTrue(process.waitFor(5, TimeUnit.SECONDS));
            assertEquals(0, process.exitValue());
        } finally {
            process.destroyForcibly();
            assertFalse(process.isAlive());
        }
    }

    private static Map<String, String> mapWithNullValue() {
        java.util.HashMap<String, String> values = new java.util.HashMap<>();
        values.put("SAFE", null);
        return values;
    }

    private static Path executable(String... candidates) throws IOException {
        for (String candidate : candidates) {
            Path path = Path.of(candidate);
            if (Files.isExecutable(path)) {
                return path.toRealPath();
            }
        }
        throw new IOException("测试可执行文件不可用");
    }

    private static boolean isMacOs() {
        return System.getProperty("os.name", "")
                .toLowerCase(java.util.Locale.ROOT)
                .contains("mac");
    }

    private static void assertInvalid(ThrowingAction action) {
        assertThrows(IllegalArgumentException.class, action::run);
    }

    @FunctionalInterface
    private interface ThrowingAction {
        void run() throws Exception;
    }
}
