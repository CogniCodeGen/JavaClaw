package com.javaclaw.nativehost.sandbox;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.javaclaw.api.CancellationSource;
import com.javaclaw.api.ResourceLimits;
import com.javaclaw.api.SandboxMode;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SandboxProcessControlTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void streamCollectorDrainsInputButKeepsOnlySharedBudget() throws Exception {
        byte[] value = "abcdef".getBytes(StandardCharsets.UTF_8);

        byte[] partial = new BoundedStreamCollector(new ByteArrayInputStream(value), new AtomicLong(3)).call();
        byte[] empty = new BoundedStreamCollector(new ByteArrayInputStream(value), new AtomicLong()).call();

        assertArrayEquals("abc".getBytes(StandardCharsets.UTF_8), partial);
        assertArrayEquals(new byte[0], empty);
        assertThrows(NullPointerException.class, () -> new BoundedStreamCollector(null, new AtomicLong()));
        assertThrows(
                NullPointerException.class, () -> new BoundedStreamCollector(new ByteArrayInputStream(value), null));
    }

    @Test
    void memoryMeterReportsLiveTreeAndZeroAfterExit() throws Exception {
        Process process =
                new ProcessBuilder(existing("/bin/sleep", "/usr/bin/sleep").toString(), "1").start();
        try {
            assertTrue(SandboxMemoryMeter.treeBytes(process) > 0);
        } finally {
            SandboxProcessTerminator.terminate(process);
        }
        assertFalse(process.isAlive());
        assertTrue(SandboxMemoryMeter.treeBytes(process) == 0);
    }

    @Test
    void monitorReportsNormalExitCancellationAndTimeout() throws Exception {
        SandboxProcessMonitor.Outcome normal = await(
                new ProcessBuilder(existing("/usr/bin/true", "/bin/true").toString()).start(),
                Duration.ofSeconds(1),
                new ResourceLimits(512L * 1024 * 1024, 1024, 1, 16),
                new CancellationSource());
        assertFalse(normal.timedOut());
        assertFalse(normal.cancelled());

        CancellationSource cancelled = new CancellationSource();
        cancelled.cancel("test");
        SandboxProcessMonitor.Outcome cancellation =
                await(sleepingProcess(), Duration.ofSeconds(2), limits(), cancelled);
        assertTrue(cancellation.cancelled());

        SandboxProcessMonitor.Outcome timeout =
                await(sleepingProcess(), Duration.ofMillis(5), limits(), new CancellationSource());
        assertTrue(timeout.timedOut());
    }

    @Test
    void monitorEnforcesMemoryAndDescendantLimits() throws Exception {
        SandboxProcessMonitor.Outcome memory = await(
                sleepingProcess(), Duration.ofSeconds(2), new ResourceLimits(1, 1024, 1, 16), new CancellationSource());
        assertTrue(memory.memoryLimitExceeded());

        Path shell = existing("/bin/sh", "/usr/bin/sh");
        Process tree = new ProcessBuilder(shell.toString(), "-c", "sleep 10 & sleep 10 & wait").start();
        SandboxProcessMonitor.Outcome descendants =
                await(tree, Duration.ofSeconds(2), limits(), new CancellationSource());
        assertTrue(descendants.processLimitExceeded());
    }

    @Test
    void terminatorEscalatesForProcessIgnoringTerminate() throws Exception {
        Path shell = existing("/bin/sh", "/usr/bin/sh");
        Process process = new ProcessBuilder(shell.toString(), "-c", "trap '' TERM; while :; do sleep 1; done").start();
        Thread.sleep(50);

        SandboxProcessTerminator.terminate(process);

        assertFalse(process.isAlive());
    }

    private SandboxProcessMonitor.Outcome await(
            Process process, Duration timeout, ResourceLimits resources, CancellationSource cancellation)
            throws Exception {
        ValidatedSandboxCommand command = new ValidatedSandboxCommand(
                "monitor",
                List.of(process.info().command().orElse("/bin/true")),
                Path.of(process.info().command().orElse("/bin/true")),
                temporaryDirectory,
                Map.of(),
                new byte[0],
                SandboxMode.BATCH,
                timeout,
                resources,
                List.of(temporaryDirectory),
                List.of(),
                List.of(Path.of(process.info().command().orElse("/bin/true"))),
                false,
                Optional.empty());
        SandboxLaunchPlan plan = new SandboxLaunchPlan("test", List.of("unused"), Map.of(), 0, false);
        return SandboxProcessMonitor.await(process, command, plan, cancellation);
    }

    private Process sleepingProcess() throws Exception {
        return new ProcessBuilder(existing("/bin/sleep", "/usr/bin/sleep").toString(), "10").start();
    }

    private static ResourceLimits limits() {
        return new ResourceLimits(512L * 1024 * 1024, 1024, 1, 16);
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
}
