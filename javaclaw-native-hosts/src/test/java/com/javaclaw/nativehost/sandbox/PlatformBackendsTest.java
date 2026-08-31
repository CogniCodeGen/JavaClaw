package com.javaclaw.nativehost.sandbox;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.javaclaw.nativehost.ffm.PosixPty;
import com.javaclaw.sandbox.api.NetworkPolicy;
import com.javaclaw.sandbox.api.SandboxCommand;
import com.javaclaw.sandbox.api.SandboxLaunchRequest;
import com.javaclaw.sandbox.api.SandboxLaunchResponse;
import com.javaclaw.sandbox.api.SandboxMode;
import com.javaclaw.sandbox.api.SandboxPaths;
import com.javaclaw.sandbox.api.SandboxPolicy;
import com.javaclaw.sandbox.api.SandboxSessionFrame;
import com.javaclaw.sandbox.api.SandboxSessionOptions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlatformBackendsTest {
    @TempDir
    Path temporary;

    @Test
    void rejectsAWorkingDirectoryInsideAProtectedRoot() {
        Path protectedRoot = temporary.resolve(".git");
        SandboxPolicy policy = new SandboxPolicy(
                SandboxMode.WORKSPACE_WRITE,
                Set.of(temporary),
                Set.of(temporary),
                Set.of(protectedRoot),
                NetworkPolicy.disabled(),
                Set.of(),
                Duration.ofSeconds(1),
                1024);
        SandboxCommand command = new SandboxCommand("id", List.of("/usr/bin/true"), protectedRoot, Map.of(), policy);

        assertThrows(IllegalArgumentException.class, () -> PlatformBackends.verify(command));
    }

    @Test
    void platformWrapperKeepsPolicyInlineAndNeverUsesATemporaryPolicyFile() {
        SandboxBackend backend = PlatformBackends.current();
        requireNativeBackend(backend);
        SandboxPolicy policy = new SandboxPolicy(
                SandboxMode.READ_ONLY,
                Set.of(temporary),
                Set.of(),
                Set.of(temporary.resolve(".git")),
                NetworkPolicy.disabled(),
                Set.of(),
                Duration.ofSeconds(1),
                1024);
        SandboxCommand command = new SandboxCommand("id", successCommand(), temporary, Map.of(), policy);

        List<String> wrapped = backend.wrap(command);

        // 包装器必须原样保留目标 argv；Windows 使用 cmd.exe，不能套用 POSIX 可执行文件断言。
        assertEquals(
                command.argv(), wrapped.subList(wrapped.size() - command.argv().size(), wrapped.size()));
        if (backend.name().equals("macos-seatbelt")) {
            String profile = wrapped.get(wrapped.indexOf("-p") + 1);
            assertTrue(profile.contains("(deny default)"));
            assertFalse(profile.contains("(allow mach-lookup)"), "普通子进程不能借任意 XPC 服务绕过网络或凭据边界");
            assertTrue(profile.contains("(allow signal (target same-sandbox))"));
            assertFalse(profile.contains("(allow process*)"));
            assertFalse(profile.contains("(local unix-socket"), "普通 Shell 不能继承 Browser 私有 IPC 能力");
            assertTrue(profile.contains(SandboxPaths.canonicalize(temporary).toString()));
        } else {
            if (backend.name().startsWith("linux-bubblewrap")) {
                assertTrue(wrapped.contains("--unshare-all"));
                assertTrue(wrapped.contains("--ro-bind"));
                assertTrue(wrapped.stream().anyMatch(value -> value.endsWith("LinuxSandboxExecMain")));
            } else {
                assertTrue(wrapped.stream().anyMatch(value -> value.endsWith("WindowsSandboxExecMain")));
                assertTrue(wrapped.contains("--protected-root"));
            }
        }
    }

    @Test
    void availableBackendExecutesAReadOnlyCommandWithBoundedOutput() throws Exception {
        SandboxBackend backend = PlatformBackends.current();
        requireNativeBackend(backend);
        SandboxPolicy policy = new SandboxPolicy(
                SandboxMode.READ_ONLY,
                Set.of(temporary),
                Set.of(),
                Set.of(temporary.resolve(".git")),
                NetworkPolicy.disabled(),
                Set.of(),
                Duration.ofSeconds(2),
                1024);
        SandboxCommand command = new SandboxCommand("id", outputCommand(), temporary, Map.of(), policy);

        var result = new SandboxProcessRunner().run(backend, command);

        if (Boolean.getBoolean("javaclaw.require.native.sandbox")) {
            assertEquals(
                    0,
                    result.exitCode(),
                    () -> "backend=" + backend.name()
                            + " stderr=" + result.stderr() + " stdout=" + result.stdout()
                            + " wrapped=" + backend.wrap(command));
        } else {
            org.junit.jupiter.api.Assumptions.assumeTrue(
                    result.exitCode() == 0, "host test runner does not permit nested sandbox: " + result.stderr());
        }
        assertEquals(0, result.exitCode(), result.stderr());
        assertEquals("sandbox-ok", result.stdout());
        assertEquals(backend.name(), result.backend());
    }

    @Test
    void isolatedLauncherProcessExecutesTheNativeBackendEndToEnd() throws Exception {
        SandboxBackend backend = PlatformBackends.current();
        requireNativeBackend(backend);
        SandboxPolicy policy = new SandboxPolicy(
                SandboxMode.READ_ONLY,
                Set.of(temporary),
                Set.of(),
                Set.of(temporary.resolve(".git")),
                NetworkPolicy.disabled(),
                Set.of(),
                Duration.ofSeconds(5),
                64 * 1024);
        SandboxCommand command = new SandboxCommand("launcher-e2e", outputCommand(), temporary, Map.of(), policy);
        ObjectMapper json = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .setSerializationInclusion(JsonInclude.Include.NON_NULL);
        List<String> launcher = List.of(
                Path.of(System.getProperty("java.home"), "bin", isWindows() ? "java.exe" : "java")
                        .toString(),
                "-cp",
                System.getProperty("java.class.path"),
                SandboxLauncherMain.class.getName());
        ProcessBuilder builder = new ProcessBuilder(launcher);
        builder.environment().clear();
        Process process = builder.start();
        try (OutputStreamWriter input = new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8)) {
            input.write(json.writeValueAsString(SandboxLaunchRequest.from("launcher-e2e-nonce", command)));
            input.write('\n');
        }
        byte[][] captured = new byte[2][];
        Thread stdout = Thread.ofVirtual().start(() -> {
            try {
                captured[0] = process.getInputStream().readAllBytes();
            } catch (Exception failure) {
                throw new IllegalStateException(failure);
            }
        });
        Thread stderr = Thread.ofVirtual().start(() -> {
            try {
                captured[1] = process.getErrorStream().readAllBytes();
            } catch (Exception failure) {
                throw new IllegalStateException(failure);
            }
        });
        assertTrue(process.waitFor(20, TimeUnit.SECONDS), "isolated launcher timed out");
        stdout.join();
        stderr.join();
        assertEquals(0, process.exitValue(), () -> new String(captured[1], StandardCharsets.UTF_8));
        SandboxLaunchResponse response =
                json.readValue(new String(captured[0], StandardCharsets.UTF_8).strip(), SandboxLaunchResponse.class);
        if (!Boolean.getBoolean("javaclaw.require.native.sandbox")) {
            org.junit.jupiter.api.Assumptions.assumeTrue(
                    response.error() == null
                            && response.result() != null
                            && response.result().exitCode() == 0,
                    "managed host denied isolated native launcher: "
                            + (response.error() == null ? response.result() : response.error()));
        }
        assertEquals(null, response.error());
        assertEquals(0, response.result().exitCode(), response.result().stderr());
        assertEquals("sandbox-ok", response.result().stdout());
    }

    @Test
    void hostFullAccessStillReappliesProtectedRootsLast() throws Exception {
        SandboxBackend backend = PlatformBackends.current();
        requireNativeBackend(backend);
        Path protectedRoot = Files.createDirectories(temporary.resolve(".git"));
        SandboxPolicy policy = new SandboxPolicy(
                SandboxMode.HOST_FULL_ACCESS,
                Set.of(),
                Set.of(),
                Set.of(protectedRoot),
                new NetworkPolicy(NetworkPolicy.Mode.FULL, Set.of()),
                Set.of(),
                Duration.ofSeconds(1),
                1024);
        SandboxCommand command = new SandboxCommand("id", successCommand(), temporary, Map.of(), policy);

        if (backend.name().equals("windows-appcontainer")) {
            assertThrows(UnsupportedOperationException.class, () -> backend.wrap(command));
            return;
        }
        List<String> wrapped = backend.wrap(command);
        Path canonicalProtectedRoot = SandboxPaths.canonicalize(protectedRoot);

        if (backend.name().equals("macos-seatbelt")) {
            String profile = wrapped.get(wrapped.indexOf("-p") + 1);
            assertTrue(profile.indexOf("(allow file-write*)")
                    < profile.indexOf("(deny file-write* (subpath \"" + canonicalProtectedRoot));
            assertTrue(profile.contains("(deny file-write* (literal \"" + canonicalProtectedRoot + "\"))"));
        } else {
            int hostBind = lastPair(wrapped, "--bind", "/");
            int protectedBind = lastPair(wrapped, "--ro-bind", canonicalProtectedRoot.toString());
            assertTrue(protectedBind > hostBind, "protected bind must override the host-wide writable bind");
            assertTrue(wrapped.contains("--cap-drop"));
        }
    }

    @Test
    void workspaceWriteAllowsOnlyGrantedFilesAndProtectsMetadata() throws Exception {
        SandboxBackend backend = PlatformBackends.current();
        requireNativeBackend(backend);
        // 与 Workspace 契约一致使用真实根；macOS 的 /var 别名不属于规范化后的授权路径。
        Path workspace = Files.createDirectories(temporary.resolve("workspace")).toRealPath();
        Path git = Files.createDirectories(workspace.resolve(".git"));
        Path configuration = Files.createDirectories(workspace.resolve(".javaclaw"));
        Path outside = Files.createDirectories(temporary.resolve("outside")).toRealPath();
        Path allowedFile = workspace.resolve("allowed.txt");
        Path protectedFile = git.resolve("blocked.txt");
        Path configurationFile = configuration.resolve("blocked.txt");
        Path escapedFile = outside.resolve("blocked.txt");
        List<String> attempts = new ArrayList<>(List.of(
                allowedFile.toString(),
                protectedFile.toString(),
                configurationFile.toString(),
                escapedFile.toString()));
        if (!isWindows()) {
            Path link = Files.createSymbolicLink(workspace.resolve("escape"), outside);
            attempts.add(link.resolve("blocked.txt").toString());
        }
        List<String> invocation;
        if (isWindows()) {
            String script = attempts.stream()
                    .map(path -> "echo attempted>\"" + path + "\"")
                    .collect(java.util.stream.Collectors.joining(" & "));
            invocation = List.of(commandInterpreter(), "/d", "/s", "/c", script);
        } else {
            // 主动尝试每个越权目标，不能以模型没有调用或包装参数包含 deny 代替 OS 拒绝证据。
            invocation = new ArrayList<>(List.of(
                    "/bin/sh", "-c", "for target; do printf attempted > \"$target\"; done; exit 0", "boundary-probe"));
            invocation.addAll(attempts);
        }
        SandboxPolicy policy = new SandboxPolicy(
                SandboxMode.WORKSPACE_WRITE,
                Set.of(workspace),
                Set.of(workspace),
                Set.of(git, configuration),
                NetworkPolicy.disabled(),
                Set.of(),
                Duration.ofSeconds(10),
                64 * 1024);
        SandboxCommand command = new SandboxCommand("workspace-boundary", invocation, workspace, Map.of(), policy);

        var result = new SandboxProcessRunner().run(backend, command);

        assertTrue(
                Files.isRegularFile(allowedFile),
                () -> "backend=" + backend.name() + " exit=" + result.exitCode() + " stderr=" + result.stderr());
        assertEquals("attempted", Files.readString(allowedFile).strip());
        assertFalse(Files.exists(protectedFile));
        assertFalse(Files.exists(configurationFile));
        assertFalse(Files.exists(escapedFile));
    }

    @Test
    void nativePtyHasAControllingTerminalAndAppliesInitialWindowSize() throws Exception {
        SandboxBackend backend = PlatformBackends.current();
        requireNativeBackend(backend);
        if (!isWindows()) {
            if (Boolean.getBoolean("javaclaw.require.native.sandbox")) {
                assertTrue(PosixPty.isSupported(), "strict POSIX PTY backend is unavailable");
            } else {
                org.junit.jupiter.api.Assumptions.assumeTrue(PosixPty.isSupported());
            }
        }
        SandboxPolicy policy = new SandboxPolicy(
                SandboxMode.READ_ONLY,
                Set.of(temporary),
                Set.of(),
                Set.of(temporary.resolve(".git")),
                NetworkPolicy.disabled(),
                Set.of(),
                Duration.ofSeconds(5),
                64 * 1024);
        List<String> terminalCommand = isWindows()
                ? List.of(commandInterpreter(), "/d", "/s", "/c", "mode con & echo pty-ok")
                : List.of("/bin/sh", "-c", "test -t 0 && test -t 1 && stty size && printf pty-ok");
        SandboxCommand command = new SandboxCommand("pty", terminalCommand, temporary, Map.of(), policy);
        ObjectMapper json = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .setSerializationInclusion(JsonInclude.Include.NON_NULL);
        ByteArrayOutputStream encoded = new ByteArrayOutputStream();
        PrintStream original = System.out;
        synchronized (PlatformBackendsTest.class) {
            try (PrintStream output = new PrintStream(encoded, true, StandardCharsets.UTF_8)) {
                System.setOut(output);
                new SandboxProcessRunner()
                        .runSession(
                                backend,
                                command,
                                SandboxSessionOptions.pty(100, 30),
                                new BufferedReader(new StringReader("")),
                                json,
                                "pty-nonce");
            } finally {
                System.setOut(original);
            }
        }
        List<SandboxSessionFrame> frames = encoded.toString(StandardCharsets.UTF_8)
                .lines()
                .filter(value -> !value.isBlank())
                .map(value -> {
                    try {
                        return json.readValue(value, SandboxSessionFrame.class);
                    } catch (Exception failure) {
                        throw new IllegalStateException(failure);
                    }
                })
                .toList();
        SandboxSessionFrame exit = frames.stream()
                .filter(value -> value.kind() == SandboxSessionFrame.Kind.EXIT)
                .findFirst()
                .orElseThrow();
        String output = frames.stream()
                .filter(value -> value.kind() == SandboxSessionFrame.Kind.STDOUT)
                .map(SandboxSessionFrame::utf8)
                .collect(java.util.stream.Collectors.joining());
        if (!Boolean.getBoolean("javaclaw.require.native.sandbox")) {
            org.junit.jupiter.api.Assumptions.assumeTrue(
                    exit.exitCode() == 0, "managed host denied nested PTY sandbox: " + output);
        }
        assertEquals(0, exit.exitCode(), output);
        if (isWindows()) {
            assertTrue(output.matches("(?s).*\\b30\\b.*\\b100\\b.*"), output);
        } else {
            assertTrue(output.contains("30 100"), output);
        }
        assertTrue(output.contains("pty-ok"), output);
    }

    private static void requireNativeBackend(SandboxBackend backend) {
        if (Boolean.getBoolean("javaclaw.require.native.sandbox")) {
            assertTrue(backend.available(), () -> "required native sandbox is unavailable: " + backend.name());
        } else {
            org.junit.jupiter.api.Assumptions.assumeTrue(backend.available());
        }
    }

    private static List<String> successCommand() {
        return isWindows() ? List.of(commandInterpreter(), "/d", "/c", "exit", "/b", "0") : List.of("/usr/bin/true");
    }

    private static List<String> outputCommand() {
        return isWindows()
                ? List.of(commandInterpreter(), "/d", "/s", "/c", "<nul set /p =sandbox-ok")
                : List.of("/usr/bin/printf", "sandbox-ok");
    }

    private static String commandInterpreter() {
        return Path.of(System.getenv().getOrDefault("SystemRoot", "C:\\Windows"), "System32", "cmd.exe")
                .toString();
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "")
                .toLowerCase(java.util.Locale.ROOT)
                .contains("windows");
    }

    private static int lastPair(List<String> values, String option, String source) {
        int found = -1;
        for (int index = 0; index + 1 < values.size(); index++) {
            if (option.equals(values.get(index)) && source.equals(values.get(index + 1))) {
                found = index;
            }
        }
        return found;
    }
}
