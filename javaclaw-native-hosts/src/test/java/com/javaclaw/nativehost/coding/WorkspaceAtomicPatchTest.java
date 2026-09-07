package com.javaclaw.nativehost.coding;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import com.javaclaw.nativehost.coding.WorkspaceFileAccess.Edit;
import com.javaclaw.nativehost.coding.WorkspaceFileAccess.Status;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledOnOs({OS.MAC, OS.LINUX})
class WorkspaceAtomicPatchTest {
    @TempDir
    Path temporary;

    @Test
    void successfulReplacementNeverExposesAMissingTargetToConcurrentReaders() throws Exception {
        Path target = Files.writeString(temporary.resolve("value"), "before");
        AtomicBoolean finished = new AtomicBoolean();
        AtomicReference<Throwable> problem = new AtomicReference<>();
        CountDownLatch observed = new CountDownLatch(1);
        Thread reader = reader(target, finished, problem, observed);
        try (var tree = new WorkspaceFileTree(temporary.toRealPath())) {
            assertTrue(observed.await(5, TimeUnit.SECONDS));
            var writer = new WorkspacePatchWriter(tree);
            for (int number = 0; number < 20; number++) {
                String before = number % 2 == 0 ? "before" : "after";
                String after = number % 2 == 0 ? "after" : "before";
                var patch = writer.prepare(List.of(edit(before, after)), 1000);
                assertEquals(Status.APPLIED, writer.apply(patch).status());
            }
        } finally {
            finished.set(true);
            reader.join(5000);
        }
        assertFalse(reader.isAlive());
        assertNull(problem.get(), () -> String.valueOf(problem.get()));
    }

    @Test
    void differingActuallyExchangedInodeStopsWithoutCounterSwappingCurrentContent() throws Exception {
        Path target = Files.writeString(temporary.resolve("value"), "before");
        try (var tree = new WorkspaceFileTree(temporary.toRealPath());
                var transaction = new WorkspacePatchTransaction(tree, () -> write(target, "racing edit"))) {
            var patch = new WorkspacePatchWriter(tree).prepare(List.of(edit("before", "after")), 1000);
            assertThrows(
                    IOException.class, () -> transaction.apply(patch.changes().getFirst()));
            assertFalse(transaction.rollback());
            Path recovery = temporary.resolve(transaction.recoveryPaths().getFirst());
            assertEquals("racing edit", Files.readString(recovery.resolve("original-0")));
            assertEquals("after", Files.readString(target));
            Files.writeString(target, "new current");
            assertFalse(transaction.rollback());
            assertEquals("new current", Files.readString(target));
        }
    }

    @Test
    void rollbackRaceRetainsTheActuallyReplacedConcurrentInodeAndRequiresRecovery() throws Exception {
        Path target = Files.writeString(temporary.resolve("value"), "before");
        AtomicInteger attempts = new AtomicInteger();
        try (var tree = new WorkspaceFileTree(temporary.toRealPath());
                var transaction = new WorkspacePatchTransaction(tree, () -> {
                    if (attempts.incrementAndGet() == 2) {
                        write(target, "racing rollback edit");
                    }
                })) {
            var patch = new WorkspacePatchWriter(tree).prepare(List.of(edit("before", "after")), 1000);
            transaction.apply(patch.changes().getFirst());
            assertFalse(transaction.rollback());
            Path recovery = temporary.resolve(transaction.recoveryPaths().getFirst());
            assertEquals("racing rollback edit", Files.readString(recovery.resolve("original-0")));
            assertEquals("before", Files.readString(target));
        }
    }

    private static Thread reader(
            Path target, AtomicBoolean finished, AtomicReference<Throwable> problem, CountDownLatch observed) {
        return Thread.ofPlatform().start(() -> {
            try {
                while (!finished.get()) {
                    String content = Files.readString(target);
                    if (!content.equals("before") && !content.equals("after")) {
                        throw new AssertionError("reader observed partial content");
                    }
                    observed.countDown();
                }
            } catch (Throwable failure) {
                problem.set(failure);
                observed.countDown();
            }
        });
    }

    private static void write(Path target, String content) {
        try {
            Files.writeString(target, content);
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    private static Edit edit(String before, String after) {
        return new Edit(
                "value",
                Optional.of(WorkspaceFileAccess.hash(before.getBytes(StandardCharsets.UTF_8))),
                Optional.of(after.getBytes(StandardCharsets.UTF_8)));
    }
}
