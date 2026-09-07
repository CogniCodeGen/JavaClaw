package com.javaclaw.nativehost.sandbox;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
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
import com.javaclaw.api.ToolPermission;
import com.javaclaw.api.ToolRisk;
import com.javaclaw.nativehost.network.SandboxNetworkAccess;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 真实 POSIX 沙箱进程验证；Windows 的隔离服务生命周期由 Windows Runner 单独验证。 */
@EnabledOnOs({OS.MAC, OS.LINUX})
class SandboxBatchObservationTest {
    @TempDir
    Path temporary;

    @Test
    void 批处理观察分离stdout和stderr且与无PTY权限的旧API兼容() throws Exception {
        var sandbox = new PlatformSandboxExecutor();
        var frames = new ArrayList<SandboxFrame>();
        var printf = command("printf", "hello");
        var permission = permission("printf", 1024);
        var observed = sandbox.execute(
                printf,
                permission,
                new CancellationSource(),
                SandboxRuntimeAccess.empty(),
                SandboxNetworkAccess.offline(),
                frames::add);
        var legacy = sandbox.execute(printf, permission, new CancellationSource());
        assertEquals(0, observed.exitCode());
        assertArrayEquals(legacy.standardOutput(), observed.standardOutput());
        assertEquals("hello", text(frames, "stdout"));
        assertEquals("", text(frames, "stderr"));
        frames.clear();
        var error = sandbox.execute(
                command("cat", temporary.resolve("missing").toString()),
                permission("cat", 1024),
                new CancellationSource(),
                SandboxRuntimeAccess.empty(),
                SandboxNetworkAccess.offline(),
                frames::add);
        assertTrue(error.exitCode() != 0);
        assertEquals(new String(error.standardError(), StandardCharsets.UTF_8), text(frames, "stderr"));
        assertEquals("", text(frames, "stdout"));
    }

    @Test
    void 进程尚存活时观察有界前缀并由同步回调形成背压() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var frames = new ArrayList<SandboxFrame>();
        var cancellation = new CancellationSource();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var running = executor.submit(() -> new PlatformSandboxExecutor()
                    .execute(
                            command("yes", "value"),
                            permission("yes", 37),
                            cancellation,
                            SandboxRuntimeAccess.empty(),
                            SandboxNetworkAccess.offline(),
                            frame -> {
                                frames.add(frame);
                                entered.countDown();
                                try {
                                    if (!release.await(2, TimeUnit.SECONDS)) {
                                        throw new IllegalStateException("fixture observer release timeout");
                                    }
                                } catch (InterruptedException interrupted) {
                                    Thread.currentThread().interrupt();
                                    throw new IllegalStateException(interrupted);
                                }
                            }));
            try {
                assertTrue(entered.await(2, TimeUnit.SECONDS));
                assertFalse(running.isDone());
                cancellation.cancel("fixture observed live output");
            } finally {
                release.countDown();
            }
            var result = running.get(3, TimeUnit.SECONDS);
            assertTrue(result.cancelled());
            assertEquals(37, result.standardOutput().length + result.standardError().length);
            assertEquals(
                    37, frames.stream().mapToInt(frame -> frame.bytes().length).sum());
            assertEquals(new String(result.standardOutput(), StandardCharsets.UTF_8), text(frames, "stdout"));
        }
    }

    @Test
    void 回调持久化失败受控终止长进程且调用线程不被中断() throws Exception {
        var rejected = new IllegalStateException("fixture output transaction failed");
        var cancellation = new CancellationSource();
        long started = System.nanoTime();
        var failure = assertThrows(
                IllegalStateException.class,
                () -> new PlatformSandboxExecutor()
                        .execute(
                                command("yes", "value"),
                                permission("yes", 1024),
                                cancellation,
                                SandboxRuntimeAccess.empty(),
                                SandboxNetworkAccess.offline(),
                                frame -> {
                                    throw rejected;
                                }));
        assertSame(rejected, failure);
        assertTrue(Duration.ofNanos(System.nanoTime() - started).compareTo(Duration.ofSeconds(3)) < 0);
        assertFalse(Thread.currentThread().isInterrupted());
        assertFalse(cancellation.isCancelled());
        var next = new PlatformSandboxExecutor()
                .execute(command("printf", "still available"), permission("printf", 1024), cancellation);
        assertEquals("still available", new String(next.standardOutput(), StandardCharsets.UTF_8));
    }

    @Test
    void 超出排空期限的观察者不被中断且明确报告清理仍未完成() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var finished = new CountDownLatch(1);
        var interrupted = new AtomicBoolean();
        Path path = temporary.resolve("shared-output");
        try (var channel = FileChannel.open(path, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
                var owner = Executors.newVirtualThreadPerTaskExecutor()) {
            var running = owner.submit(() -> new PlatformSandboxExecutor()
                    .execute(
                            command("printf", "observed"),
                            permission("printf", 1024),
                            new CancellationSource(),
                            SandboxRuntimeAccess.empty(),
                            SandboxNetworkAccess.offline(),
                            frame -> sharedWrite(channel, entered, release, finished, interrupted)));
            try {
                assertTrue(entered.await(2, TimeUnit.SECONDS));
                var failure = assertThrows(ExecutionException.class, () -> running.get(13, TimeUnit.SECONDS));
                assertTrue(failure.getCause() instanceof IOException);
                assertTrue(failure.getCause().getMessage().contains("output did not drain"));
                assertTrue(java.util.Arrays.stream(failure.getCause().getSuppressed())
                        .anyMatch(cleanup -> cleanup.getMessage().contains("observer cleanup incomplete")));
                assertEquals(1, finished.getCount());
                assertFalse(interrupted.get());
                assertTrue(channel.isOpen());
            } finally {
                release.countDown();
            }
            assertTrue(finished.await(2, TimeUnit.SECONDS));
            assertTrue(channel.isOpen());
            assertEquals("persisted", Files.readString(path));
        }
    }

    @Test
    void 外部中断执行线程不会传递给持有共享通道的观察者() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var finished = new CountDownLatch(1);
        var interrupted = new AtomicBoolean();
        var callerFlag = new AtomicBoolean();
        var failed = new AtomicReference<Throwable>();
        Path path = temporary.resolve("shared-interrupted-output");
        try (var channel = FileChannel.open(path, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            Thread owner = Thread.startVirtualThread(() -> {
                try {
                    new PlatformSandboxExecutor()
                            .execute(
                                    command("printf", "observed"),
                                    permission("printf", 1024),
                                    new CancellationSource(),
                                    SandboxRuntimeAccess.empty(),
                                    SandboxNetworkAccess.offline(),
                                    frame -> sharedWrite(channel, entered, release, finished, interrupted));
                } catch (Exception failure) {
                    failed.set(failure);
                } finally {
                    callerFlag.set(Thread.currentThread().isInterrupted());
                }
            });
            try {
                assertTrue(entered.await(2, TimeUnit.SECONDS));
                owner.interrupt();
                assertTrue(channel.isOpen());
            } finally {
                release.countDown();
            }
            owner.join(Duration.ofSeconds(3));
            assertFalse(owner.isAlive());
            // 释放观察者与 Future 完成允许竞争；执行可已完成，但外部中断标志必须仍由调用线程拥有。
            assertTrue(
                    failed.get() == null || failed.get() instanceof InterruptedException,
                    () -> String.valueOf(failed.get()));
            assertTrue(callerFlag.get());
            assertFalse(interrupted.get());
            assertTrue(channel.isOpen());
            assertEquals("persisted", Files.readString(path));
        }
    }

    private static void sharedWrite(
            FileChannel channel,
            CountDownLatch entered,
            CountDownLatch release,
            CountDownLatch finished,
            AtomicBoolean interrupted) {
        entered.countDown();
        try {
            try {
                release.await();
            } catch (InterruptedException unexpected) {
                interrupted.set(true);
                Thread.currentThread().interrupt();
            }
            // 若观察者被 Future.cancel(true) 中断，真实 FileChannel 写入会关闭共享通道；不能模拟掉该副作用。
            channel.write(ByteBuffer.wrap("persisted".getBytes(StandardCharsets.UTF_8)));
        } catch (IOException failure) {
            throw new IllegalStateException(failure);
        } finally {
            finished.countDown();
        }
    }

    private SandboxCommand command(String name, String argument) throws Exception {
        Path executable =
                Files.isExecutable(Path.of("/usr/bin", name)) ? Path.of("/usr/bin", name) : Path.of("/bin", name);
        return new SandboxCommand(
                "observed-batch",
                List.of(executable.toRealPath().toString(), argument),
                temporary.toRealPath(),
                Map.of("LANG", "C"),
                new byte[0],
                SandboxMode.BATCH,
                Duration.ofSeconds(5));
    }

    private PermissionProfile permission(String name, long outputBytes) throws Exception {
        return new PermissionProfile(
                "observed-batch",
                1,
                new FilePermission(List.of(temporary.toRealPath()), List.of(), false, false),
                new NetworkPermission(Set.of(), Set.of(), true),
                new ProcessPermission(Set.of(name), false, Duration.ofSeconds(5)),
                new ToolPermission(Set.of(), ToolRisk.EXTERNAL_EFFECT, ApprovalRequirement.EVERY_CALL),
                new ResourceLimits(512L * 1024 * 1024, outputBytes, 1, 64));
    }

    private static String text(List<SandboxFrame> frames, String channel) {
        var bytes = new java.io.ByteArrayOutputStream();
        frames.stream()
                .filter(frame -> frame.channel().equals(channel))
                .forEach(frame -> bytes.writeBytes(frame.bytes()));
        return bytes.toString(StandardCharsets.UTF_8);
    }
}
