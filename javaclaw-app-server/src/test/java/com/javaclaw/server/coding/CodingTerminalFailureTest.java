package com.javaclaw.server.coding;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.javaclaw.builtin.contracts.CodingContracts;
import com.javaclaw.builtin.contracts.CodingResults;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CodingTerminalFailureTest {
    @TempDir
    Path directory;

    @Test
    void nativeStartupFailurePreservesUnknownOutcomeAndReleasesTheProcessSlot() throws Exception {
        var sandbox = new ControlledCodingSandbox();
        sandbox.openFailure = new IllegalStateException("native startup did not return a session");
        try (var fixture = new CodingLifecycleFixture(directory, sandbox)) {
            assertThrows(IllegalStateException.class, () -> open(fixture));
            assertEquals(
                    "UNKNOWN_OUTCOME",
                    fixture.records.read(fixture.base.workspace.id(), "pty").state());
            var result = fixture.terminals.snapshot(fixture.base.workspace.id(), "pty", 0, 1024);
            assertEquals(CodingResults.ProcessState.FAILED, result.state());
            assertTrue(result.output().stdout().startsWith("[UNKNOWN_OUTCOME:"));
            assertFalse(result.output().stdout().contains("服务重启"));
            assertTrue(result.exitCode().isEmpty());
            assertEquals(0, result.output().nextOffsetBytes());
            assertEquals(1, fixture.toolchains.released.get());
            try (var available = fixture.locks.acquire(fixture.base.turn.id(), fixture.base.root)) {
                assertEquals(0, sandbox.session.closes.get());
            }
        }
    }

    @Test
    void initialResizeFailureOnALiveSessionClosesItBeforeTheOpenCallReturns() throws Exception {
        var sandbox = new ControlledCodingSandbox();
        sandbox.session.resizeFails = true;
        try (var fixture = new CodingLifecycleFixture(directory, sandbox)) {
            assertThrows(ExecutionException.class, () -> open(fixture));
            assertEquals(1, sandbox.session.closes.get());
            assertEquals(1, fixture.toolchains.released.get());
            assertEquals(
                    "CANCELLED",
                    fixture.records.read(fixture.base.workspace.id(), "pty").state());
            try (var available = fixture.locks.acquire(fixture.base.turn.id(), fixture.base.root)) {
                assertEquals(1, sandbox.starts.get());
            }
        }
    }

    @Test
    void outputFailureKeepsAlreadyCommittedBytesAndMarksTheExecutionFailed() throws Exception {
        var sandbox = new ControlledCodingSandbox();
        try (var fixture = new CodingLifecycleFixture(directory, sandbox)) {
            open(fixture);
            sandbox.session.emit("retained tail");
            sandbox.session.failOutput(new IllegalStateException("PTY output transport failed"));
            var result = awaitFinal(fixture);
            assertEquals(CodingResults.ProcessState.FAILED, result.state());
            assertEquals("retained tail", result.output().stdout());
            assertEquals(-1, result.exitCode().orElseThrow());
            assertEquals(1, sandbox.session.closes.get());
            assertEquals(1, fixture.toolchains.released.get());
        }
    }

    @Test
    void outputFloodStopsTheSessionWithoutExceedingThePersistedBudget() throws Exception {
        var sandbox = new ControlledCodingSandbox();
        try (var fixture = new CodingLifecycleFixture(directory, sandbox)) {
            open(fixture);
            sandbox.session.emit("a".repeat(1024 * 1024 + 1));
            var result = awaitFinal(fixture);
            assertEquals(CodingResults.ProcessState.FAILED, result.state());
            assertEquals(
                    65536,
                    fixture.records.read(fixture.base.workspace.id(), "pty").outputBytes());
            assertTrue(result.output().truncated());
            assertEquals(1024, result.output().nextOffsetBytes());
            assertEquals("a".repeat(1024), result.output().stdout());
            // 终态后迟到的输出帧不能重开日志或突破已经冻结的尾部。
            sandbox.session.emit("late output");
            assertEquals(
                    65536,
                    fixture.records.read(fixture.base.workspace.id(), "pty").outputBytes());
            assertEquals(1, sandbox.session.closes.get());
        }
    }

    @Test
    void lostCompletionDoesNotInventAnExitCodeOrRestartTheProcess() throws Exception {
        var sandbox = new ControlledCodingSandbox();
        try (var fixture = new CodingLifecycleFixture(directory, sandbox)) {
            open(fixture);
            sandbox.session.emit("before disconnect");
            sandbox.session.exit.completeExceptionally(new IllegalStateException("exit transport lost"));
            var result = awaitFinal(fixture);
            assertTrue(result.exitCode().isEmpty());
            assertEquals(CodingResults.ProcessState.FAILED, result.state());
            assertTrue(result.output().stdout().contains("未恢复 PID 或 stdin"));
            assertTrue(result.output().stdout().endsWith("before disconnect"));
            var tail = fixture.terminals.snapshot(fixture.base.workspace.id(), "pty", 7, 1024);
            assertFalse(tail.output().stdout().contains("UNKNOWN_OUTCOME"));
            assertEquals("disconnect", tail.output().stdout());
            assertEquals(1, sandbox.starts.get());
        }
    }

    private static void open(CodingLifecycleFixture fixture) throws Exception {
        var command = new CodingContracts.CommandRun(List.of("java", "-version"), ".", 10, 65536);
        fixture.terminals.execute(
                "terminal_open",
                fixture.invocation("pty", "terminal_open", new CodingContracts.TerminalOpen(command, 80, 24)));
    }

    private static CodingResults.TerminalResult awaitFinal(CodingLifecycleFixture fixture) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            var result = fixture.terminals.snapshot(fixture.base.workspace.id(), "pty", 0, 1024);
            if (result.state() != CodingResults.ProcessState.RUNNING) {
                return result;
            }
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(10));
        }
        throw new AssertionError("PTY did not reach a final state");
    }
}
