package com.javaclaw.nativehost.sandbox;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.javaclaw.api.ResourceLimits;
import com.javaclaw.api.SandboxMode;
import com.javaclaw.nativehost.network.SandboxNetworkAccess;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SandboxHelperAndBuilderTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void helperArgumentsPreserveTargetAndValidateNumericBounds() {
        SandboxHelperArguments arguments = SandboxHelperArguments.parse(
                new String[] {"1500", "4096", "2048", "2", "8", "--", "/bin/echo", "hello world"});

        assertEquals(Duration.ofMillis(1500), arguments.timeout());
        assertEquals(new ResourceLimits(4096, 2048, 2, 8), arguments.limits());
        assertEquals(List.of("/bin/echo", "hello world"), arguments.target());
        assertThrows(IllegalArgumentException.class, () -> SandboxHelperArguments.parse(new String[] {"1"}));
        assertThrows(
                IllegalArgumentException.class,
                () -> SandboxHelperArguments.parse(new String[] {"1", "1", "1", "1", "1", "wrong", "target"}));
        assertThrows(
                IllegalArgumentException.class,
                () -> SandboxHelperArguments.parse(new String[] {"0", "1", "1", "1", "1", "--", "target"}));
        assertThrows(
                NumberFormatException.class,
                () -> SandboxHelperArguments.parse(new String[] {"x", "1", "1", "1", "1", "--", "target"}));
        assertThrows(
                ArithmeticException.class,
                () -> SandboxHelperArguments.parse(new String[] {"1", "1", "1", "2147483648", "1", "--", "target"}));
        assertThrows(
                IllegalArgumentException.class,
                () -> new SandboxHelperArguments(Duration.ofSeconds(1), new ResourceLimits(1, 1, 1, 1), List.of()));
    }

    @Test
    void linuxBuilderCreatesNamespacedBatchPlan() throws Exception {
        Path executable = existing("/bin/echo", "/usr/bin/echo");
        Path writeRoot = Files.createDirectory(temporaryDirectory.resolve("output"));
        ValidatedSandboxCommand command = validated(executable, SandboxMode.BATCH, List.of(writeRoot), true);

        SandboxLaunchPlan plan = new LinuxSandboxCommandBuilder(executable).build(command);

        assertEquals("linux-bubblewrap", plan.backend());
        assertEquals(1, plan.trustedDescendantProcesses());
        assertTrue(plan.command().contains("--unshare-all"));
        assertTrue(plan.command().contains("--new-session"));
        assertTrue(plan.command().contains("--bind"));
        assertTrue(plan.command().contains(writeRoot.toString()));
        assertTrue(plan.command().contains("--setenv"));
        assertTrue(plan.command().contains(SandboxExecMain.class.getName()));
        assertEquals(Map.of(), plan.environment());
    }

    @Test
    void linuxBuilderRejectsMissingBackendAndAmbiguousWritePolicy() throws Exception {
        Path executable = existing("/bin/echo", "/usr/bin/echo");
        Path writeRoot = Files.createDirectory(temporaryDirectory.resolve("write"));
        ValidatedSandboxCommand command = validated(executable, SandboxMode.BATCH, List.of(writeRoot), false);

        assertThrows(
                UnsupportedOperationException.class,
                () -> new LinuxSandboxCommandBuilder(temporaryDirectory.resolve("missing")).build(command));
        assertThrows(
                UnsupportedOperationException.class, () -> new LinuxSandboxCommandBuilder(executable).build(command));
    }

    @Test
    void linuxPtyPlanOmitsNewSessionAndSelectsPtyHelper() throws Exception {
        Path executable = existing("/bin/echo", "/usr/bin/echo");
        ValidatedSandboxCommand command = validated(executable, SandboxMode.PTY, List.of(), false);

        SandboxLaunchPlan plan = new LinuxSandboxCommandBuilder(executable).build(command);

        assertFalse(plan.command().contains("--new-session"));
        assertTrue(plan.command().contains(SandboxPtyExecMain.class.getName()));
    }

    @Test
    void windowsBuilderUsesNativeTreeLimitsAndPreservesExplicitEnvironment() throws Exception {
        Path executable = existing("/bin/echo", "/usr/bin/echo");
        ValidatedSandboxCommand command = validated(executable, SandboxMode.BATCH, List.of(), false);

        SandboxLaunchPlan plan = new WindowsSandboxCommandBuilder(SandboxHelperCommand.javaExecutable()).build(command);

        assertEquals("windows-appcontainer-job", plan.backend());
        assertTrue(plan.nativeTreeLimits());
        assertEquals(1, plan.trustedDescendantProcesses());
        assertEquals(command.environment(), plan.environment());
        assertTrue(plan.command().contains(WindowsSandboxExecMain.class.getName()));
        assertTrue(plan.command().contains("--"));
        assertTrue(plan.command().containsAll(command.argv()));
    }

    @Test
    void windowsHelperArgumentsReconstructOneV6Request() {
        String root = temporaryDirectory.toString();
        var request = WindowsSandboxHelperArguments.parse(new String[] {
            root,
            "false",
            "1500",
            "4096",
            "2048",
            "2",
            "8",
            "1",
            "1",
            root,
            root,
            "--",
            "C:\\Tools\\worker.exe",
            "hello world"
        });

        assertEquals(List.of("C:\\Tools\\worker.exe", "hello world"), request.arguments());
        assertEquals(
                temporaryDirectory.toAbsolutePath().normalize(),
                request.context().workingDirectory());
        assertEquals(List.of(temporaryDirectory.toAbsolutePath().normalize()), request.readRoots());
        assertEquals(List.of(temporaryDirectory.toAbsolutePath().normalize()), request.writeRoots());
        assertFalse(request.allowDelete());
        assertEquals(Duration.ofMillis(1500), request.timeout());
        assertEquals(new ResourceLimits(4096, 2048, 2, 8), request.limits());
        assertThrows(
                IllegalArgumentException.class,
                () -> WindowsSandboxHelperArguments.parse(new String[] {root, "false"}));
        assertThrows(
                IllegalArgumentException.class,
                () -> WindowsSandboxHelperArguments.parse(
                        new String[] {root, "maybe", "1", "1", "1", "1", "1", "0", "0", "--", "worker"}));
    }

    @Test
    void platformSelectionIsFailClosed() {
        String original = System.getProperty("os.name");
        try {
            System.setProperty("os.name", "Mac OS X");
            assertInstanceOf(MacSandboxCommandBuilder.class, PlatformSandboxCommandBuilder.current());
            System.setProperty("os.name", "Linux");
            assertInstanceOf(LinuxSandboxCommandBuilder.class, PlatformSandboxCommandBuilder.current());
            System.setProperty("os.name", "Windows 11");
            assertInstanceOf(WindowsSandboxCommandBuilder.class, PlatformSandboxCommandBuilder.current());
            System.setProperty("os.name", "Plan 9");
            assertThrows(UnsupportedOperationException.class, PlatformSandboxCommandBuilder::current);
        } finally {
            restore("os.name", original);
        }
    }

    @Test
    void internalValueObjectsDefensivelyCopyMutableInputs() throws Exception {
        Path executable = existing("/bin/echo", "/usr/bin/echo");
        byte[] input = new byte[] {1, 2};
        ValidatedSandboxCommand command = new ValidatedSandboxCommand(
                "copy",
                List.of(executable.toString()),
                executable,
                temporaryDirectory,
                Map.of(),
                input,
                SandboxMode.BATCH,
                Duration.ofSeconds(1),
                new ResourceLimits(4096, 4096, 1, 8),
                List.of(temporaryDirectory),
                List.of(),
                List.of(executable),
                false,
                Optional.empty(),
                SandboxNetworkAccess.offline());
        input[0] = 9;
        byte[] returned = command.standardInput();
        returned[1] = 9;

        assertArrayEquals(new byte[] {1, 2}, command.standardInput());
        assertEquals(
                temporaryDirectory.resolve("terminal").toAbsolutePath().normalize(),
                command.withTerminal(temporaryDirectory.resolve("terminal"))
                        .terminal()
                        .orElseThrow());
        assertThrows(
                IllegalArgumentException.class, () -> new SandboxLaunchPlan("bad", List.of(), Map.of(), -1, false));
    }

    private ValidatedSandboxCommand validated(
            Path executable, SandboxMode mode, List<Path> writeRoots, boolean allowDelete) {
        return new ValidatedSandboxCommand(
                "linux-plan",
                List.of(executable.toString(), "hello"),
                executable,
                temporaryDirectory,
                Map.of("LANG", "C"),
                new byte[0],
                mode,
                Duration.ofSeconds(2),
                new ResourceLimits(64L * 1024 * 1024, 4096, 2, 32),
                List.of(temporaryDirectory),
                writeRoots,
                List.of(executable),
                allowDelete,
                Optional.empty(),
                SandboxNetworkAccess.offline());
    }

    private static Path existing(String... candidates) throws Exception {
        for (String candidate : candidates) {
            Path path = Path.of(candidate);
            if (Files.isExecutable(path)) {
                return path.toRealPath();
            }
        }
        throw new IllegalStateException("test executable is unavailable");
    }

    private static void restore(String name, String value) {
        if (value == null) {
            System.clearProperty(name);
        } else {
            System.setProperty(name, value);
        }
    }
}
