package com.javaclaw.server.coding;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.javaclaw.api.CancellationSource;
import com.javaclaw.api.CancellationToken;
import com.javaclaw.builtin.contracts.CodingContracts;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CodingPlatformLifecycleTest {
    @TempDir
    Path directory;

    @Test
    void aPreviouslyBoundPatchCannotStartAfterTheTurnScopeCloses() throws Exception {
        try (var fixture = new CodingTestFixture(directory);
                var binding = fixture.platform.bindTool(
                        fixture.request(fixture.turn, "file_apply_patch", patch(), "late"),
                        fixture.permission,
                        new CancellationSource())) {
            fixture.platform.finish(fixture.turn.id());
            assertThrows(IllegalStateException.class, binding::invoke);
            assertFalse(Files.exists(fixture.root.resolve("new.txt")));
        }
    }

    @Test
    void cancellingAnEnteredFileCallWaitsForItsOwnerAndPreventsTheWrite() throws Exception {
        try (var fixture = new CodingTestFixture(directory);
                var tasks = Executors.newVirtualThreadPerTaskExecutor()) {
            var cancellation = new PausedCancellation();
            try (var binding = fixture.platform.bindTool(
                    fixture.request(fixture.turn, "file_apply_patch", patch(), "entered"),
                    fixture.permission,
                    cancellation)) {
                var operation = tasks.submit(binding::invoke);
                assertTrue(cancellation.entered.await(5, TimeUnit.SECONDS));
                var finish = tasks.submit(() -> {
                    fixture.platform.finish(fixture.turn.id());
                    return null;
                });
                try {
                    assertThrows(TimeoutException.class, () -> finish.get(100, TimeUnit.MILLISECONDS));
                } finally {
                    cancellation.release.countDown();
                }
                assertThrows(java.util.concurrent.ExecutionException.class, () -> operation.get(5, TimeUnit.SECONDS));
                finish.get(5, TimeUnit.SECONDS);
                assertFalse(Files.exists(fixture.root.resolve("new.txt")));
            }
        }
    }

    private static CodingContracts.ApplyPatch patch() {
        return new CodingContracts.ApplyPatch(List.of(new CodingContracts.FileEdit(
                "new.txt", Optional.empty(), Optional.of("must not be written"), Optional.empty())));
    }

    private static final class PausedCancellation implements CancellationToken {
        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);

        @Override
        public boolean isCancelled() {
            entered.countDown();
            try {
                if (!release.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("test did not release the entered file call");
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(interrupted);
            }
            return false;
        }

        @Override
        public Optional<String> reason() {
            return Optional.empty();
        }
    }
}
