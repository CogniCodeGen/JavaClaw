package com.javaclaw.server.coding;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.javaclaw.api.CancellationSource;
import com.javaclaw.api.CancellationToken;
import com.javaclaw.api.SandboxSignal;
import com.javaclaw.api.TurnCancelledException;
import com.javaclaw.builtin.contracts.CodingContracts;
import com.javaclaw.builtin.contracts.CodingResults;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CodingTerminalInteractionTest {
    @TempDir
    Path directory;

    @Test
    void repeatedInputIsDeliveredOnceAndTheSessionReceivesExactSignalAndDimensions() throws Exception {
        var sandbox = new ControlledCodingSandbox();
        try (var fixture = new CodingLifecycleFixture(directory, sandbox)) {
            open(fixture);
            var input = new CodingContracts.TerminalWrite("pty", "中文\n", 1);
            invoke(fixture, "first-input", "terminal_write", input);
            invoke(fixture, "repeat-input", "terminal_write", input);
            assertThrows(
                    SecurityException.class,
                    () -> invoke(
                            fixture,
                            "different-input",
                            "terminal_write",
                            new CodingContracts.TerminalWrite("pty", "别的输入", 1)));
            invoke(
                    fixture,
                    "signal",
                    "terminal_signal",
                    new CodingContracts.TerminalSignal("pty", SandboxSignal.INTERRUPT));
            invoke(fixture, "resize", "terminal_resize", new CodingContracts.TerminalResize("pty", 120, 40));
            assertEquals(List.of("中文\n"), sandbox.session.inputs);
            assertEquals(List.of(SandboxSignal.INTERRUPT), sandbox.session.signals);
            assertEquals(List.of("80x24", "120x40"), sandbox.session.dimensions);

            sandbox.session.emit("abc中文");
            var first =
                    invoke(fixture, "first-page", "terminal_read", new CodingContracts.TerminalRead("pty", 0, 3, 0));
            var tail = invoke(fixture, "tail", "terminal_read", new CodingContracts.TerminalRead("pty", 3, 6, 0));
            assertEquals("abc", first.output().stdout());
            assertEquals(3, first.output().nextOffsetBytes());
            assertTrue(first.output().truncated());
            assertEquals("中文", tail.output().stdout());
            assertEquals(9, tail.output().nextOffsetBytes());
            assertFalse(tail.output().truncated());

            var closed = invoke(fixture, "close", "terminal_close", new CodingContracts.TerminalClose("pty"));
            assertEquals(CodingResults.ProcessState.CANCELLED, closed.state());
            invoke(fixture, "close-again", "terminal_close", new CodingContracts.TerminalClose("pty"));
            assertEquals(1, sandbox.session.closes.get());
            assertThrows(IllegalStateException.class, () -> invoke(fixture, "late-input", "terminal_write", input));
        }
    }

    @Test
    void aReadWaitsForNewBytesAndCancellationStopsTheWaitWithoutClosingAnotherOwner() throws Exception {
        var sandbox = new ControlledCodingSandbox();
        try (var fixture = new CodingLifecycleFixture(directory, sandbox);
                var tasks = Executors.newVirtualThreadPerTaskExecutor()) {
            open(fixture);
            var observed = new ObservedCancellation();
            var waiting = withCancellation(
                    fixture.invocation(
                            "waiting", "terminal_read", new CodingContracts.TerminalRead("pty", 0, 20, 2000)),
                    observed);
            var read = tasks.submit(() -> fixture.terminals.execute("terminal_read", waiting));
            assertTrue(observed.observed.await(5, TimeUnit.SECONDS));
            sandbox.session.emit("new bytes");
            var result = assertInstanceOf(
                    CodingResults.TerminalResult.class,
                    read.get(5, TimeUnit.SECONDS).value());
            assertEquals("new bytes", result.output().stdout());

            var cancelled = new CancellationSource();
            cancelled.cancel("取消等待读取");
            var request = withCancellation(
                    fixture.invocation(
                            "cancelled-read", "terminal_read", new CodingContracts.TerminalRead("pty", 9, 20, 2000)),
                    cancelled);
            assertThrows(TurnCancelledException.class, () -> fixture.terminals.execute("terminal_read", request));
            var empty = invoke(fixture, "deadline", "terminal_read", new CodingContracts.TerminalRead("pty", 9, 20, 1));
            assertEquals("", empty.output().stdout());
            assertEquals(9, empty.output().nextOffsetBytes());
            assertEquals(0, sandbox.session.closes.get());
        }
    }

    @Test
    void anotherTurnCannotReadOrControlTheTerminalEvenWithinTheSameWorkspace() throws Exception {
        var sandbox = new ControlledCodingSandbox();
        try (var fixture = new CodingLifecycleFixture(directory, sandbox)) {
            open(fixture);
            var other = fixture.base.createTurn("other-owner");
            var original = fixture.invocation(
                    "other-read", "terminal_read", new CodingContracts.TerminalRead("pty", 0, 10, 0));
            var request = fixture.base.request(
                    other, "terminal_read", new CodingContracts.TerminalRead("pty", 0, 10, 0), "other-read");
            var crossTurn = new CodingInvocation(
                    original.id(),
                    request,
                    other,
                    original.workspaceId(),
                    original.permission(),
                    original.cancellation(),
                    original.environment());
            assertThrows(SecurityException.class, () -> fixture.terminals.execute("terminal_read", crossTurn));
            assertThrows(
                    IllegalStateException.class,
                    () -> fixture.processes.run(fixture.invocation("occupied-slot", "command_run", command())));
            assertEquals(1, sandbox.starts.get());
            assertEquals(0, sandbox.session.closes.get());
            invoke(fixture, "close", "terminal_close", new CodingContracts.TerminalClose("pty"));
            fixture.processes.run(fixture.invocation("released-slot", "command_run", command()));
            assertEquals(2, sandbox.starts.get());
        }
    }

    @Test
    void uncertainInputDeliveryClosesTheProcessAndCannotReplayStdin() throws Exception {
        var sandbox = new ControlledCodingSandbox();
        sandbox.session.sendFailure = new IllegalStateException("write may already have reached the process");
        try (var fixture = new CodingLifecycleFixture(directory, sandbox)) {
            open(fixture);
            var input = new CodingContracts.TerminalWrite("pty", "side effect\n", 1);
            assertThrows(ExecutionException.class, () -> invoke(fixture, "uncertain-write", "terminal_write", input));
            assertThrows(IllegalStateException.class, () -> invoke(fixture, "retry-write", "terminal_write", input));
            fixture.terminals.finish(fixture.base.turn.id());
            assertEquals(List.of("side effect\n"), sandbox.session.inputs);
            var owner = fixture.records.read(fixture.base.workspace.id(), "pty");
            assertEquals("CANCELLED", owner.state());
            // 发送成功与入账之间的未知结果不能被当成未发送；持久记录也拒绝重放。
            String digest = fixture.base.json.encode(input).sha256();
            assertThrows(SecurityException.class, () -> fixture.records.inputIntent(owner, 1, digest));
            assertEquals(1, sandbox.session.closes.get());
        }
    }

    @Test
    void duplicateAndFinishedTurnOpenNeverAcquireASecondNativeSession() throws Exception {
        var sandbox = new ControlledCodingSandbox();
        try (var fixture = new CodingLifecycleFixture(directory, sandbox)) {
            var invocation =
                    fixture.invocation("pty", "terminal_open", new CodingContracts.TerminalOpen(command(), 80, 24));
            fixture.terminals.execute("terminal_open", invocation);
            assertThrows(IllegalStateException.class, () -> fixture.terminals.execute("terminal_open", invocation));
            fixture.terminals.finish(fixture.base.turn.id());
            assertThrows(
                    IllegalStateException.class,
                    () -> fixture.terminals.execute(
                            "terminal_open",
                            fixture.invocation(
                                    "after-turn",
                                    "terminal_open",
                                    new CodingContracts.TerminalOpen(command(), 80, 24))));
            fixture.terminals.close();
            assertThrows(
                    IllegalStateException.class,
                    () -> fixture.terminals.execute(
                            "terminal_open",
                            fixture.invocation(
                                    "after-manager",
                                    "terminal_open",
                                    new CodingContracts.TerminalOpen(command(), 80, 24))));
            assertEquals(1, sandbox.starts.get());
            assertEquals(1, fixture.toolchains.released.get());
        }
    }

    private static void open(CodingLifecycleFixture fixture) throws Exception {
        fixture.terminals.execute(
                "terminal_open",
                fixture.invocation("pty", "terminal_open", new CodingContracts.TerminalOpen(command(), 80, 24)));
    }

    private static CodingResults.TerminalResult invoke(
            CodingLifecycleFixture fixture, String id, String tool, Object input) throws Exception {
        return assertInstanceOf(
                CodingResults.TerminalResult.class,
                fixture.terminals
                        .execute(tool, fixture.invocation(id, tool, input))
                        .value());
    }

    private static CodingContracts.CommandRun command() {
        return new CodingContracts.CommandRun(List.of("java", "-version"), ".", 10, 65536);
    }

    private static CodingInvocation withCancellation(CodingInvocation original, CancellationToken cancellation) {
        return new CodingInvocation(
                original.id(),
                original.request(),
                original.turn(),
                original.workspaceId(),
                original.permission(),
                cancellation,
                original.environment());
    }

    private static final class ObservedCancellation implements CancellationToken {
        private final CountDownLatch observed = new CountDownLatch(1);

        @Override
        public boolean isCancelled() {
            observed.countDown();
            return false;
        }

        @Override
        public Optional<String> reason() {
            return Optional.empty();
        }
    }
}
