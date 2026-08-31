package com.javaclaw.agent.tool;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.javaclaw.sandbox.api.SandboxCommand;
import com.javaclaw.sandbox.api.SandboxPolicy;
import com.javaclaw.sandbox.api.SandboxSessionFrame;
import com.javaclaw.sandbox.api.SandboxSessionOptions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LauncherProcessSandboxExecutorTest {
    @TempDir
    Path temporary;

    @Test
    void preservesEachIdeModulePathIncludingSpacesAndUnicode() {
        Path classes = temporary.resolve("模块 空格/target/classes");
        Path dependency = temporary.resolve("依赖/jackson.jar");
        List<String> command = LauncherProcessSandboxExecutor.modularJavaCommand(List.of(classes, dependency));

        assertEquals(
                classes.toAbsolutePath().normalize()
                        + File.pathSeparator
                        + dependency.toAbsolutePath().normalize(),
                command.get(command.indexOf("--module-path") + 1));
        assertEquals("--enable-native-access=com.javaclaw.nativehosts", command.get(1));
        assertEquals(
                LauncherProcessSandboxExecutor.modularJavaCommand(classes),
                LauncherProcessSandboxExecutor.modularJavaCommand(List.of(classes)));
        assertThrows(
                IllegalArgumentException.class, () -> LauncherProcessSandboxExecutor.modularJavaCommand(List.of()));
    }

    @Test
    void authenticatesOneShotLauncherResponseWithNonce() throws Exception {
        LauncherProcessSandboxExecutor executor = new LauncherProcessSandboxExecutor(fakeLauncher());

        var result = executor.execute(command());

        assertEquals("from launcher", result.stdout());
        assertEquals("fake", result.backend());
    }

    @Test
    void rejectsResponseFromWrongLauncherConversation() {
        List<String> command = new ArrayList<>(fakeLauncher());
        command.add("wrong-nonce");
        LauncherProcessSandboxExecutor executor = new LauncherProcessSandboxExecutor(command);

        assertThrows(SandboxExecutionException.class, () -> executor.execute(command()));
    }

    @Test
    void opensAuthenticatedLongLivedPipeSession() throws Exception {
        LauncherProcessSandboxExecutor executor = new LauncherProcessSandboxExecutor(fakeLauncher());

        try (var session = executor.openSession(command(), SandboxSessionOptions.pipes())) {
            session.write("ping\n".getBytes(StandardCharsets.UTF_8));
            SandboxSessionFrame output = session.read(Duration.ofSeconds(2));
            assertEquals(SandboxSessionFrame.Kind.STDOUT, output.kind());
            assertEquals("ping\n", output.utf8());
        }
    }

    @Test
    void forwardsPtyDimensionsToAnExactLauncherBackend() throws Exception {
        LauncherProcessSandboxExecutor executor = new LauncherProcessSandboxExecutor(fakeLauncher());

        try (var session = executor.openSession(command(), SandboxSessionOptions.pty(120, 40))) {
            session.write("ping\n".getBytes(StandardCharsets.UTF_8));
            SandboxSessionFrame output = session.read(Duration.ofSeconds(2));
            assertEquals(SandboxSessionFrame.Kind.STDOUT, output.kind());
            assertEquals("120x40:ping\n", output.utf8());
        }
    }

    @Test
    void cancellationDoesNotWaitForBlockedStdinAndStillKillsAProcessWithClosedOutput() throws Exception {
        for (String mode : List.of("blocked-session", "closed-output")) {
            var command = new ArrayList<>(fakeLauncher());
            command.add(mode);
            var executor = new LauncherProcessSandboxExecutor(command);
            try (var session = executor.openSession(command(), SandboxSessionOptions.pipes())) {
                var writer = java.util.concurrent.CompletableFuture.runAsync(
                        () -> {
                            try {
                                session.write(new byte[1024 * 1024]);
                            } catch (Exception expected) {
                                // 已关闭/被取消的管道必须唤醒写者，不能阻止进程树回收。
                            }
                        },
                        task -> Thread.ofVirtual().start(task));
                assertTimeoutPreemptively(Duration.ofSeconds(5), session::terminate);
                writer.get(2, java.util.concurrent.TimeUnit.SECONDS);
                assertFalse(session.isAlive());
            }
        }
    }

    @Test
    void rejectsOversizedUnterminatedFrameBeforeUnboundedAllocation() throws Exception {
        var command = new ArrayList<>(fakeLauncher());
        command.add("oversized-frame");
        var executor = new LauncherProcessSandboxExecutor(command);
        try (var session = executor.openSession(command(), SandboxSessionOptions.pipes())) {
            var failure = session.read(Duration.ofSeconds(5));
            assertEquals(SandboxSessionFrame.Kind.ERROR, failure.kind());
            assertTrue(failure.detail().contains("frame exceeds limit"));
        }
    }

    private List<String> fakeLauncher() {
        return List.of(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp",
                System.getProperty("java.class.path"),
                FakeSandboxLauncher.class.getName());
    }

    private SandboxCommand command() {
        return new SandboxCommand(
                "command",
                List.of("ignored"),
                temporary,
                Map.of(),
                SandboxPolicy.readOnly(Set.of(temporary), Set.of()));
    }
}
