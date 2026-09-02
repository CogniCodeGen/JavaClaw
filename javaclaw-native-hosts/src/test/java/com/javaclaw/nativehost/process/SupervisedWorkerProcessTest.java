package com.javaclaw.nativehost.process;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import com.javaclaw.api.CancellationSource;
import com.javaclaw.api.CanonicalPayload;
import com.javaclaw.api.TurnCancelledException;
import com.javaclaw.protocol.ProtocolException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SupervisedWorkerProcessTest {
    @Test
    void exchangesCanonicalFramesAndReusesWorker() {
        try (SupervisedWorkerProcess worker = worker("echo", Duration.ofSeconds(2))) {
            CanonicalPayload first = worker.exchange(new CanonicalPayload("{\"value\":1}"), new CancellationSource());
            CanonicalPayload second = worker.exchange(new CanonicalPayload("{\"value\":2}"), new CancellationSource());

            assertEquals("{\"value\":1}", first.json());
            assertEquals("{\"value\":2}", second.json());
        }
    }

    @Test
    void rejectsInvalidConstructionAndUseAfterClose() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new SupervisedWorkerProcess(List.of(), Duration.ofSeconds(1), 1024));
        assertThrows(
                IllegalArgumentException.class,
                () -> new SupervisedWorkerProcess(List.of("java", " "), Duration.ofSeconds(1), 1024));
        assertThrows(
                IllegalArgumentException.class,
                () -> new SupervisedWorkerProcess(List.of("java"), Duration.ofMillis(999), 1024));
        assertThrows(
                IllegalArgumentException.class,
                () -> new SupervisedWorkerProcess(List.of("java"), Duration.ofSeconds(121), 1024));
        assertThrows(
                IllegalArgumentException.class,
                () -> new SupervisedWorkerProcess(List.of("java"), Duration.ofSeconds(1), 1023));

        SupervisedWorkerProcess worker = worker("echo", Duration.ofSeconds(2));
        worker.close();
        worker.close();
        assertThrows(IllegalStateException.class, () -> worker.exchange(payload(), new CancellationSource()));
    }

    @Test
    void reportsStartExitAndMalformedResponseFailures() {
        try (SupervisedWorkerProcess missing =
                new SupervisedWorkerProcess(List.of("/javaclaw/missing-worker"), Duration.ofSeconds(1), 1024)) {
            assertThrows(WorkerProcessException.class, () -> missing.exchange(payload(), new CancellationSource()));
        }
        try (SupervisedWorkerProcess exiting = worker("exit", Duration.ofSeconds(2))) {
            assertThrows(WorkerProcessException.class, () -> exiting.exchange(payload(), new CancellationSource()));
        }
        try (SupervisedWorkerProcess malformed = worker("malformed", Duration.ofSeconds(2))) {
            assertThrows(ProtocolException.class, () -> malformed.exchange(payload(), new CancellationSource()));
        }
    }

    @Test
    void timesOutAndAbortsBlockedWorker() {
        try (SupervisedWorkerProcess worker = worker("sleep", Duration.ofSeconds(1))) {
            WorkerProcessException failure = assertThrows(
                    WorkerProcessException.class, () -> worker.exchange(payload(), new CancellationSource()));
            assertTrue(failure.getMessage().contains("timed out"));
        }
    }

    @Test
    void observesCancellationWhileWaitingForWorker() throws Exception {
        CancellationSource cancellation = new CancellationSource();
        try (SupervisedWorkerProcess worker = worker("sleep", Duration.ofSeconds(5));
                var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var result = executor.submit(() -> worker.exchange(payload(), cancellation));
            Thread.sleep(100);
            cancellation.cancel("test cancellation");

            ExecutionException failure = assertThrows(ExecutionException.class, () -> result.get(2, TimeUnit.SECONDS));
            assertTrue(failure.getCause() instanceof TurnCancelledException);
        }
    }

    @Test
    void rejectsAlreadyCancelledRequestBeforeStartingWorker() {
        CancellationSource cancellation = new CancellationSource();
        cancellation.cancel("already cancelled");
        try (SupervisedWorkerProcess worker = worker("echo", Duration.ofSeconds(2))) {
            assertThrows(TurnCancelledException.class, () -> worker.exchange(payload(), cancellation));
        }
    }

    private static SupervisedWorkerProcess worker(String mode, Duration timeout) {
        String classPath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        Path java = Path.of(System.getProperty("java.home"), "bin", executableName());
        return new SupervisedWorkerProcess(
                List.of(java.toString(), "-cp", classPath, WorkerProtocolTestMain.class.getName(), mode),
                timeout,
                4096);
    }

    private static String executableName() {
        return System.getProperty("os.name", "")
                        .toLowerCase(java.util.Locale.ROOT)
                        .contains("windows")
                ? "java.exe"
                : "java";
    }

    private static CanonicalPayload payload() {
        return new CanonicalPayload("{\"request\":true}");
    }
}
