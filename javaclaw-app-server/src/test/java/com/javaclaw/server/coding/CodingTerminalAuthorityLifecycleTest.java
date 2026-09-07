package com.javaclaw.server.coding;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.javaclaw.api.CancellationToken;
import com.javaclaw.builtin.contracts.CodingContracts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CodingTerminalAuthorityLifecycleTest {
    @TempDir
    Path directory;

    @Test
    void finishWaitsForAnEnteredAuthorityCheckWithoutInterruptingItsSharedResources() throws Exception {
        var sandbox = new ControlledCodingSandbox();
        var observation = new PausedAuthorityCheck();
        try (var fixture = new CodingLifecycleFixture(directory, sandbox);
                var tasks = Executors.newVirtualThreadPerTaskExecutor()) {
            var command = new CodingContracts.CommandRun(List.of("java", "-version"), ".", 10, 65536);
            var original =
                    fixture.invocation("authority", "terminal_open", new CodingContracts.TerminalOpen(command, 80, 24));
            var invocation = new CodingInvocation(
                    original.id(),
                    original.request(),
                    original.turn(),
                    original.workspaceId(),
                    original.permission(),
                    observation,
                    original.environment());
            fixture.terminals.execute("terminal_open", invocation);
            observation.armed = true;
            assertTrue(observation.entered.await(5, TimeUnit.SECONDS));
            var finish = tasks.submit(() -> {
                fixture.terminals.finish(fixture.base.turn.id());
                return null;
            });
            try {
                assertThrows(TimeoutException.class, () -> finish.get(100, TimeUnit.MILLISECONDS));
                assertFalse(observation.interrupted);
            } finally {
                observation.release.countDown();
            }
            finish.get(5, TimeUnit.SECONDS);
            assertTrue(observation.returned);
            assertFalse(observation.interrupted);
            assertEquals(1, sandbox.session.closes.get());
            assertEquals(1, fixture.toolchains.released.get());
            try (var available = fixture.locks.acquire(fixture.base.turn.id(), fixture.base.root)) {
                assertEquals(
                        "CANCELLED",
                        fixture.records
                                .read(fixture.base.workspace.id(), "authority")
                                .state());
            }
        } finally {
            observation.release.countDown();
        }
    }

    private static final class PausedAuthorityCheck implements CancellationToken {
        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private volatile boolean armed;
        private volatile boolean interrupted;
        private volatile boolean returned;

        @Override
        public boolean isCancelled() {
            if (armed && Thread.currentThread().getName().equals("javaclaw-pty-authority")) {
                entered.countDown();
                try {
                    if (!release.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("test did not release authority observation");
                    }
                } catch (InterruptedException failure) {
                    interrupted = true;
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(failure);
                } finally {
                    returned = true;
                }
            }
            return false;
        }

        @Override
        public Optional<String> reason() {
            return Optional.empty();
        }
    }
}
