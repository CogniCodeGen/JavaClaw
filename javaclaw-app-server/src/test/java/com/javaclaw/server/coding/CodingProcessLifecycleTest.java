package com.javaclaw.server.coding;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.javaclaw.builtin.contracts.CodingContracts;
import com.javaclaw.builtin.contracts.CodingResults;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CodingProcessLifecycleTest {
    @TempDir
    Path directory;

    @Test
    void batchFinishWaitsForToolchainAndRootLeaseRelease() throws Exception {
        var sandbox = new ControlledCodingSandbox();
        try (var fixture = new CodingLifecycleFixture(directory, sandbox);
                var tasks = Executors.newVirtualThreadPerTaskExecutor()) {
            CountDownLatch closingLease = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            fixture.toolchains.beforeRelease = () -> hold(closingLease, release);
            var invocation = fixture.invocation("batch", "command_run", command());
            var run = tasks.submit(() -> fixture.processes.run(invocation));
            assertTrue(closingLease.await(5, TimeUnit.SECONDS));
            var finish = tasks.submit(() -> {
                fixture.processes.finish(fixture.base.turn.id());
                return null;
            });
            try {
                assertThrows(TimeoutException.class, () -> finish.get(100, TimeUnit.MILLISECONDS));
                assertThrows(IllegalStateException.class, () -> fixture.locks.acquire(fixture.base.root));
            } finally {
                release.countDown();
            }
            run.get(5, TimeUnit.SECONDS);
            finish.get(5, TimeUnit.SECONDS);
            assertEquals(1, fixture.toolchains.released.get());
            try (var available = fixture.locks.acquire(fixture.base.root)) {
                assertEquals(1, sandbox.starts.get());
            }
            var late = fixture.invocation("late", "command_run", command());
            assertThrows(IllegalStateException.class, () -> fixture.processes.run(late));
        }
    }

    @Test
    void terminalFinishPreservesTailAndWaitsForAllLeases() throws Exception {
        var sandbox = new ControlledCodingSandbox();
        sandbox.session.completeOutputOnClose = false;
        try (var fixture = new CodingLifecycleFixture(directory, sandbox);
                var tasks = Executors.newVirtualThreadPerTaskExecutor()) {
            CountDownLatch closingLease = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            fixture.toolchains.beforeRelease = () -> hold(closingLease, release);
            fixture.terminals.execute("terminal_open", fixture.invocation("pty", "terminal_open", terminal()));
            sandbox.session.emit("trailing output\n");
            sandbox.session.exit.complete(ControlledCodingSandbox.success());
            var finish = tasks.submit(() -> {
                fixture.terminals.finish(fixture.base.turn.id());
                return null;
            });
            try {
                assertThrows(TimeoutException.class, () -> finish.get(100, TimeUnit.MILLISECONDS));
                sandbox.session.finishOutput();
                assertTrue(closingLease.await(5, TimeUnit.SECONDS));
                assertThrows(TimeoutException.class, () -> finish.get(100, TimeUnit.MILLISECONDS));
            } finally {
                sandbox.session.finishOutput();
                release.countDown();
            }
            finish.get(5, TimeUnit.SECONDS);
            var result = fixture.terminals.snapshot(fixture.base.workspace.id(), "pty", 0, 1024);
            assertEquals(CodingResults.ProcessState.COMPLETED, result.state());
            assertEquals("trailing output\n", result.output().stdout());
            assertEquals(1, fixture.toolchains.released.get());
            assertEquals(1, sandbox.session.closes.get());
        }
    }

    @Test
    void commandExitingBeforeInitialResizeStillKeepsItsRealResult() throws Exception {
        var sandbox = new ControlledCodingSandbox();
        sandbox.session.shortCommand = true;
        sandbox.session.resizeFails = true;
        try (var fixture = new CodingLifecycleFixture(directory, sandbox)) {
            fixture.terminals.execute("terminal_open", fixture.invocation("short", "terminal_open", terminal()));
            fixture.terminals.finish(fixture.base.turn.id());

            var result = fixture.terminals.snapshot(fixture.base.workspace.id(), "short", 0, 1024);
            assertEquals(CodingResults.ProcessState.COMPLETED, result.state());
            assertEquals(0, result.exitCode().orElseThrow());
            assertEquals("short command output", result.output().stdout());
            assertEquals(1, sandbox.session.closes.get());
        }
    }

    @Test
    void terminalCleanupFailureStillReleasesRootAndAggregatesToolchainFailure() throws Exception {
        var sandbox = new ControlledCodingSandbox();
        try (var fixture = new CodingLifecycleFixture(directory, sandbox);
                var tasks = Executors.newVirtualThreadPerTaskExecutor()) {
            CountDownLatch closing = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            sandbox.session.beforeClose = () -> {
                hold(closing, release);
                throw new IllegalStateException("native-close-failed");
            };
            fixture.toolchains.beforeRelease = () -> {
                throw new IllegalStateException("lease-close-failed");
            };
            fixture.terminals.execute("terminal_open", fixture.invocation("failure", "terminal_open", terminal()));
            sandbox.session.exit.complete(ControlledCodingSandbox.success());
            sandbox.session.finishOutput();
            assertTrue(closing.await(5, TimeUnit.SECONDS));
            var finish = tasks.submit(() -> {
                fixture.terminals.finish(fixture.base.turn.id());
                return null;
            });
            try {
                assertThrows(TimeoutException.class, () -> finish.get(100, TimeUnit.MILLISECONDS));
            } finally {
                release.countDown();
            }
            Exception failure = assertThrows(ExecutionException.class, () -> finish.get(5, TimeUnit.SECONDS));
            assertTrue(contains(failure, "native-close-failed"));
            assertTrue(contains(failure, "lease-close-failed"));
            assertEquals(1, fixture.toolchains.released.get());
            try (var available = fixture.locks.acquire(fixture.base.root)) {
                assertEquals(
                        "FAILED",
                        fixture.records
                                .read(fixture.base.workspace.id(), "failure")
                                .state());
            }
        }
    }

    @Test
    void finishDuringPreparationPreventsLateNativeStartAndWaitsForRelease() throws Exception {
        var sandbox = new ControlledCodingSandbox();
        try (var fixture = new CodingLifecycleFixture(directory, sandbox);
                var tasks = Executors.newVirtualThreadPerTaskExecutor()) {
            CountDownLatch acquiring = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            fixture.toolchains.beforeAcquire = () -> hold(acquiring, release);
            var invocation = fixture.invocation("opening", "terminal_open", terminal());
            var open = tasks.submit(() -> fixture.terminals.execute("terminal_open", invocation));
            assertTrue(acquiring.await(5, TimeUnit.SECONDS));
            var finish = tasks.submit(() -> {
                fixture.terminals.finish(fixture.base.turn.id());
                return null;
            });
            try {
                assertThrows(TimeoutException.class, () -> finish.get(100, TimeUnit.MILLISECONDS));
            } finally {
                release.countDown();
            }
            assertThrows(ExecutionException.class, () -> open.get(5, TimeUnit.SECONDS));
            finish.get(5, TimeUnit.SECONDS);
            assertEquals(0, sandbox.starts.get());
            assertEquals(1, fixture.toolchains.released.get());
        }
    }

    @Test
    void anEarlierFailureDoesNotSkipAwaitingTheRemainingOwners() throws Exception {
        CompletableFuture<Void> first = CompletableFuture.failedFuture(new IllegalStateException("first"));
        CompletableFuture<Void> second = new CompletableFuture<>();
        try (var tasks = Executors.newVirtualThreadPerTaskExecutor()) {
            var cleanup = tasks.submit(() -> {
                CodingCleanup.awaitAll(List.of(first, second));
                return null;
            });
            try {
                assertThrows(TimeoutException.class, () -> cleanup.get(100, TimeUnit.MILLISECONDS));
            } finally {
                second.completeExceptionally(new IllegalStateException("second"));
            }
            Exception failure = assertThrows(ExecutionException.class, () -> cleanup.get(5, TimeUnit.SECONDS));
            assertTrue(contains(failure, "first"));
            assertTrue(contains(failure, "second"));
        }
    }

    private CodingContracts.CommandRun command() {
        return new CodingContracts.CommandRun(List.of("java", "-version"), ".", 10, 65536);
    }

    private CodingContracts.TerminalOpen terminal() {
        return new CodingContracts.TerminalOpen(command(), 80, 24);
    }

    private static void hold(CountDownLatch entered, CountDownLatch release) {
        entered.countDown();
        try {
            if (!release.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("test release timeout");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        }
    }

    private static boolean contains(Throwable failure, String text) {
        if (failure == null) {
            return false;
        }
        if (text.equals(failure.getMessage()) || contains(failure.getCause(), text)) {
            return true;
        }
        return java.util.Arrays.stream(failure.getSuppressed()).anyMatch(suppressed -> contains(suppressed, text));
    }
}
