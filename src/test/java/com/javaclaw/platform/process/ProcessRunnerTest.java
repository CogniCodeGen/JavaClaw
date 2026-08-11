package com.javaclaw.platform.process;

import com.javaclaw.platform.execution.ManagedTaskExecutor;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProcessRunnerTest {

    @Test
    void capturesBothStreamsAndExitCode() throws Exception {
        try (ManagedTaskExecutor executor = new ManagedTaskExecutor()) {
            ProcessRunner runner = new ProcessRunner(executor);
            ProcessResult result = runner.run(ProcessRequest.argv("capture",
                    List.of("/bin/sh", "-c", "printf out; printf err >&2; exit 7"),
                    Duration.ofSeconds(2)));

            assertEquals(7, result.exitCode());
            assertEquals("out", result.stdout());
            assertEquals("err", result.stderr());
            assertFalse(result.timedOut());
            assertFalse(result.succeeded());
        }
    }

    @Test
    void truncatesRetainedOutputWithoutBlockingProcess() throws Exception {
        try (ManagedTaskExecutor executor = new ManagedTaskExecutor()) {
            ProcessRunner runner = new ProcessRunner(executor);
            ProcessRequest request = ProcessRequest.argv("bounded-output",
                    List.of("/bin/sh", "-c", "printf 1234567890"), Duration.ofSeconds(2))
                    .withOutputLimit(4);

            ProcessResult result = runner.run(request);

            assertEquals("1234", result.stdout());
            assertTrue(result.outputTruncated());
            assertTrue(result.succeeded());
        }
    }

    @Test
    void timeoutTerminatesProcessAndReturnsExplicitState() throws Exception {
        try (ManagedTaskExecutor executor = new ManagedTaskExecutor()) {
            ProcessRunner runner = new ProcessRunner(executor);
            long started = System.nanoTime();

            ProcessResult result = runner.run(ProcessRequest.argv("timeout",
                    List.of("/bin/sh", "-c", "sleep 10"), Duration.ofMillis(100)));

            assertTrue(result.timedOut());
            assertFalse(result.succeeded());
            assertTrue(Duration.ofNanos(System.nanoTime() - started)
                    .compareTo(Duration.ofSeconds(4)) < 0);
        }
    }
}
