package com.javaclaw.nativehost.sandbox;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.javaclaw.api.ApprovalRequirement;
import com.javaclaw.api.CancellationSource;
import com.javaclaw.api.FilePermission;
import com.javaclaw.api.NetworkPermission;
import com.javaclaw.api.PermissionProfile;
import com.javaclaw.api.ProcessPermission;
import com.javaclaw.api.ResourceLimits;
import com.javaclaw.api.SandboxCommand;
import com.javaclaw.api.SandboxFrame;
import com.javaclaw.api.SandboxMode;
import com.javaclaw.api.SandboxResult;
import com.javaclaw.api.SandboxSession;
import com.javaclaw.api.SandboxSignal;
import com.javaclaw.api.ToolPermission;
import com.javaclaw.api.ToolRisk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlatformSandboxExecutorTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void validatorRejectsRelativeExecutablesAndRawNetworkPermissions() {
        PermissionProfile permission = permission("echo", new NetworkPermission(Set.of(), Set.of(), true), 1024);
        SandboxCommand relative = command(Path.of("echo"), "hello");
        assertThrows(
                SecurityException.class,
                () -> SandboxPolicyValidator.validate(relative, permission, SandboxMode.BATCH));

        PermissionProfile networked =
                permission("echo", new NetworkPermission(Set.of("example.com"), Set.of(443), true), 1024);
        SandboxCommand absolute = command(existing("/bin/echo", "/usr/bin/echo"), "hello");
        assertThrows(
                UnsupportedOperationException.class,
                () -> SandboxPolicyValidator.validate(absolute, networked, SandboxMode.BATCH));
    }

    @Test
    void batchExecutionUsesOsSandboxAndEnforcesSharedOutputLimit() throws Exception {
        Path printf = existing("/usr/bin/printf", "/bin/printf");
        PermissionProfile permission = permission("printf", new NetworkPermission(Set.of(), Set.of(), true), 4096);
        SandboxCommand command = command(printf, "abcdefgh");

        SandboxResult result = new PlatformSandboxExecutor().execute(command, permission, new CancellationSource());

        assertEquals(0, result.exitCode(), new String(result.standardError(), StandardCharsets.UTF_8));
        assertEquals("abcdefgh", new String(result.standardOutput(), StandardCharsets.UTF_8));
        assertFalse(result.timedOut());
        assertFalse(result.cancelled());
    }

    @Test
    void batchExecutionWritesOnlyInsideConfiguredRoot() throws Exception {
        Path touch = existing("/usr/bin/touch", "/bin/touch");
        Path output = temporaryDirectory.resolve("created.txt");
        PermissionProfile permission = new PermissionProfile(
                "sandbox-write-test",
                1,
                new FilePermission(List.of(temporaryDirectory), List.of(temporaryDirectory), false, false),
                new NetworkPermission(Set.of(), Set.of(), true),
                new ProcessPermission(Set.of("touch"), false, Duration.ofSeconds(5)),
                new ToolPermission(Set.of(), ToolRisk.EXTERNAL_EFFECT, ApprovalRequirement.EVERY_CALL),
                new ResourceLimits(512L * 1024 * 1024, 4096, 1, 64));
        SandboxCommand command = command(touch, output.toString());

        SandboxResult result = new PlatformSandboxExecutor().execute(command, permission, new CancellationSource());

        assertEquals(0, result.exitCode(), new String(result.standardError(), StandardCharsets.UTF_8));
        assertTrue(Files.isRegularFile(output));
    }

    @Test
    void ptyExecutionProvidesControllingTerminalAndBackpressuredFrames() throws Exception {
        Path tty = existing("/usr/bin/tty", "/bin/tty");
        PermissionProfile permission = permission("tty", new NetworkPermission(Set.of(), Set.of(), true), 4096, true);
        SandboxCommand command = new SandboxCommand(
                "pty-test",
                List.of(tty.toString()),
                temporaryDirectory,
                Map.of("LANG", "C"),
                new byte[0],
                SandboxMode.PTY,
                Duration.ofSeconds(3));
        CollectingSubscriber subscriber = new CollectingSubscriber();

        try (SandboxSession session =
                new PlatformSandboxExecutor().open(command, permission, new CancellationSource())) {
            session.frames().subscribe(subscriber);
            SandboxResult result = session.completion().toCompletableFuture().get(5, TimeUnit.SECONDS);

            assertEquals(0, result.exitCode(), new String(result.standardOutput(), StandardCharsets.UTF_8));
            assertTrue(subscriber.await());
            assertTrue(subscriber.text().contains("/dev/"));
        }
    }

    @Test
    void batchExecutionReturnsTimeoutAndLiveCancellation() throws Exception {
        Path sleep = existing("/bin/sleep", "/usr/bin/sleep");
        PermissionProfile permission = permission("sleep", new NetworkPermission(Set.of(), Set.of(), true), 4096);
        SandboxCommand timed = command(sleep, "10", SandboxMode.BATCH, new byte[0], Duration.ofMillis(30));

        SandboxResult timeout = new PlatformSandboxExecutor().execute(timed, permission, new CancellationSource());
        assertTrue(timeout.timedOut());
        assertEquals(-1, timeout.exitCode());

        CancellationSource cancellation = new CancellationSource();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var running = executor.submit(
                    () -> new PlatformSandboxExecutor().execute(command(sleep, "10"), permission, cancellation));
            Thread.sleep(100);
            cancellation.cancel("test cancellation");
            SandboxResult cancelled = running.get(3, TimeUnit.SECONDS);
            assertTrue(cancelled.cancelled());
            assertEquals(-1, cancelled.exitCode());
        }
    }

    @Test
    void batchExecutionTransfersStandardInputAndSeparatesError() throws Exception {
        Path cat = existing("/bin/cat", "/usr/bin/cat");
        PermissionProfile permission =
                permission(cat.getFileName().toString(), new NetworkPermission(Set.of(), Set.of(), true), 4096);
        byte[] input = "from-input\n".getBytes(StandardCharsets.UTF_8);
        SandboxCommand command = command(cat, List.of(), SandboxMode.BATCH, input, Duration.ofSeconds(2));

        SandboxResult result = new PlatformSandboxExecutor().execute(command, permission, new CancellationSource());
        SandboxResult error = new PlatformSandboxExecutor()
                .execute(
                        command(cat, temporaryDirectory.resolve("missing").toString()),
                        permission,
                        new CancellationSource());

        assertEquals("from-input\n", new String(result.standardOutput(), StandardCharsets.UTF_8));
        assertTrue(new String(error.standardError(), StandardCharsets.UTF_8).contains("missing"));
    }

    @Test
    void ptySessionSupportsSendResizeAndSignal() throws Exception {
        Path cat = existing("/bin/cat", "/usr/bin/cat");
        PermissionProfile permission =
                permission(cat.getFileName().toString(), new NetworkPermission(Set.of(), Set.of(), true), 4096, true);
        SandboxCommand command = command(cat, List.of(), SandboxMode.PTY, new byte[0], Duration.ofSeconds(3));
        CollectingSubscriber subscriber = new CollectingSubscriber();

        try (SandboxSession session =
                new PlatformSandboxExecutor().open(command, permission, new CancellationSource())) {
            session.frames().subscribe(subscriber);
            session.resize(90, 30).toCompletableFuture().get(2, TimeUnit.SECONDS);
            session.send("hello\n".getBytes(StandardCharsets.UTF_8))
                    .toCompletableFuture()
                    .get(2, TimeUnit.SECONDS);
            assertTrue(subscriber.awaitOccurrences("hello", 2));
            session.signal(SandboxSignal.INTERRUPT).toCompletableFuture().get(2, TimeUnit.SECONDS);
            SandboxResult result = session.completion().toCompletableFuture().get(5, TimeUnit.SECONDS);
            assertFalse(result.timedOut(), subscriber.text());
        }
    }

    @Test
    void ptySessionMapsAllSignalsAndClosesIdempotently() throws Exception {
        for (SandboxSignal signal : SandboxSignal.values()) {
            exerciseSignal(signal);
        }

        Path cat = existing("/bin/cat", "/usr/bin/cat");
        PermissionProfile permission =
                permission(cat.getFileName().toString(), new NetworkPermission(Set.of(), Set.of(), true), 4096, true);
        SandboxSession session = new PlatformSandboxExecutor()
                .open(
                        command(
                                cat,
                                List.of(),
                                SandboxMode.PTY,
                                "ready\n".getBytes(StandardCharsets.UTF_8),
                                Duration.ofSeconds(5)),
                        permission,
                        new CancellationSource());
        CollectingSubscriber subscriber = new CollectingSubscriber();
        session.frames().subscribe(subscriber);
        assertTrue(subscriber.awaitOccurrences("ready", 2));
        session.close();
        session.close();
        assertThrows(
                ExecutionException.class,
                () -> session.send(new byte[0]).toCompletableFuture().get(1, TimeUnit.SECONDS));
    }

    @Test
    void ptySessionStopsAtConfiguredOutputLimit() throws Exception {
        Path yes = existing("/usr/bin/yes", "/bin/yes");
        PermissionProfile permission = permission("yes", new NetworkPermission(Set.of(), Set.of(), true), 1024, true);
        SandboxCommand command = command(yes, "x", SandboxMode.PTY, new byte[0], Duration.ofSeconds(3));

        try (SandboxSession session =
                new PlatformSandboxExecutor().open(command, permission, new CancellationSource())) {
            SandboxResult result = session.completion().toCompletableFuture().get(5, TimeUnit.SECONDS);
            assertEquals(-1, result.exitCode());
            assertEquals(1024, result.standardOutput().length);
        }
    }

    private void exerciseSignal(SandboxSignal signal) throws Exception {
        Path cat = existing("/bin/cat", "/usr/bin/cat");
        PermissionProfile permission =
                permission(cat.getFileName().toString(), new NetworkPermission(Set.of(), Set.of(), true), 4096, true);
        SandboxCommand command = command(
                cat, List.of(), SandboxMode.PTY, "ready\n".getBytes(StandardCharsets.UTF_8), Duration.ofSeconds(5));
        CollectingSubscriber subscriber = new CollectingSubscriber();
        try (SandboxSession session =
                new PlatformSandboxExecutor().open(command, permission, new CancellationSource())) {
            session.frames().subscribe(subscriber);
            assertTrue(subscriber.awaitOccurrences("ready", 2));
            session.signal(signal).toCompletableFuture().get(2, TimeUnit.SECONDS);
            SandboxResult result = session.completion().toCompletableFuture().get(5, TimeUnit.SECONDS);
            assertFalse(result.timedOut());
        }
    }

    private SandboxCommand command(Path executable, String argument) {
        return command(executable, argument, SandboxMode.BATCH, new byte[0], Duration.ofSeconds(3));
    }

    private SandboxCommand command(Path executable, String argument, SandboxMode mode, byte[] input, Duration timeout) {
        return command(executable, List.of(argument), mode, input, timeout);
    }

    private SandboxCommand command(
            Path executable, List<String> arguments, SandboxMode mode, byte[] input, Duration timeout) {
        java.util.ArrayList<String> argv = new java.util.ArrayList<>();
        argv.add(executable.toString());
        argv.addAll(arguments);
        return new SandboxCommand("sandbox-test", argv, temporaryDirectory, Map.of("LANG", "C"), input, mode, timeout);
    }

    private PermissionProfile permission(String executable, NetworkPermission network, long outputBytes) {
        return permission(executable, network, outputBytes, false);
    }

    private PermissionProfile permission(
            String executable, NetworkPermission network, long outputBytes, boolean allowPty) {
        return new PermissionProfile(
                "sandbox-test",
                1,
                new FilePermission(List.of(temporaryDirectory), List.of(), false, false),
                network,
                new ProcessPermission(Set.of(executable), allowPty, Duration.ofSeconds(5)),
                new ToolPermission(Set.of(), ToolRisk.EXTERNAL_EFFECT, ApprovalRequirement.EVERY_CALL),
                new ResourceLimits(512L * 1024 * 1024, outputBytes, 1, 64));
    }

    private static Path existing(String... candidates) {
        for (String candidate : candidates) {
            Path path = Path.of(candidate);
            if (Files.isExecutable(path)) {
                try {
                    return path.toRealPath();
                } catch (java.io.IOException failure) {
                    throw new IllegalStateException(failure);
                }
            }
        }
        throw new IllegalStateException("test executable is unavailable");
    }

    private static final class CollectingSubscriber implements Flow.Subscriber<SandboxFrame> {
        private final ByteArrayOutputStream output = new ByteArrayOutputStream();
        private final CountDownLatch completed = new CountDownLatch(1);

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            subscription.request(Long.MAX_VALUE);
        }

        @Override
        public synchronized void onNext(SandboxFrame item) {
            output.writeBytes(item.bytes());
        }

        @Override
        public void onError(Throwable throwable) {
            completed.countDown();
        }

        @Override
        public void onComplete() {
            completed.countDown();
        }

        private boolean await() throws InterruptedException {
            return completed.await(2, TimeUnit.SECONDS);
        }

        private synchronized String text() {
            return output.toString(StandardCharsets.UTF_8);
        }

        private boolean awaitOccurrences(String expected, int count) throws InterruptedException {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (System.nanoTime() < deadline) {
                if (occurrences(text(), expected) >= count) {
                    return true;
                }
                Thread.sleep(10);
            }
            return false;
        }

        private static int occurrences(String source, String expected) {
            int count = 0;
            int offset = 0;
            while ((offset = source.indexOf(expected, offset)) >= 0) {
                count++;
                offset += expected.length();
            }
            return count;
        }
    }
}
