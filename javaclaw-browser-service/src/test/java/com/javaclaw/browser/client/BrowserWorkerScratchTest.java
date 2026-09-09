package com.javaclaw.browser.client;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SecureDirectoryStream;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.javaclaw.api.ResourceLimits;
import com.javaclaw.nativehost.sandbox.SandboxedWorkerCommand;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BrowserWorkerScratchTest {
    private static final ResourceLimits LIMITS = new ResourceLimits(256L * 1024 * 1024, 4096, 4, 128);

    @TempDir
    Path temporaryDirectory;

    @Test
    void 并发启动获得独立根且首个退出不清理第二个实例() throws Exception {
        Path parent = directory("scratch-parent");
        SandboxedWorkerCommand template = template(parent).withPrivateScratch(parent);
        List<SandboxedWorkerCommand> commands = new CopyOnWriteArrayList<>();
        Map<Path, PendingProcess> processes = new ConcurrentHashMap<>();
        CyclicBarrier concurrent = new CyclicBarrier(2);
        BrowserWorkerLauncher launcher = new BrowserWorkerLauncher(template, command -> {
            commands.add(command);
            PendingProcess process = new PendingProcess();
            processes.put(command.workingDirectory(), process);
            rendezvous(concurrent);
            return process;
        });
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(launcher::start);
            var second = executor.submit(launcher::start);
            first.get(5, TimeUnit.SECONDS);
            second.get(5, TimeUnit.SECONDS);
        }
        assertNotEquals(commands.get(0).workingDirectory(), commands.get(1).workingDirectory());
        for (SandboxedWorkerCommand command : commands) {
            assertIsolated(template, command, parent);
        }
        Path secondRoot = commands.get(1).workingDirectory();
        Files.writeString(secondRoot.resolve("profile"), "second-instance");
        processes.get(commands.get(0).workingDirectory()).finish();
        awaitDeleted(commands.get(0).workingDirectory());
        assertEquals("second-instance", Files.readString(secondRoot.resolve("profile")));
        Files.delete(secondRoot.resolve("profile"));
        processes.get(secondRoot).finish();
        awaitDeleted(secondRoot);
        assertTrue(Files.isDirectory(parent));
    }

    @Test
    void 启动失败清理本次目录并保留链接外目标和相邻实例() throws Exception {
        Path parent = directory("scratch-parent");
        Path sibling = Files.createDirectory(parent.resolve("already-running"));
        Path outside = Files.writeString(temporaryDirectory.resolve("outside"), "preserved");
        AtomicReference<Path> allocated = new AtomicReference<>();
        BrowserWorkerLauncher launcher =
                new BrowserWorkerLauncher(template(parent).withPrivateScratch(parent), command -> {
                    Path root = command.workingDirectory();
                    allocated.set(root);
                    Files.createSymbolicLink(root.resolve("outside-link"), outside);
                    Files.writeString(
                            Files.createDirectories(root.resolve("nested")).resolve("profile"), "fixture");
                    throw new IOException("expected start failure");
                });

        IOException failure = assertThrows(IOException.class, launcher::start);

        assertEquals("expected start failure", failure.getMessage());
        assertEquals("preserved", Files.readString(outside));
        assertTrue(Files.isDirectory(sibling));
        if (secureDirectories(parent)) {
            assertFalse(Files.exists(allocated.get()));
        } else {
            assertTrue(Files.isDirectory(allocated.get()));
            assertEquals(1, failure.getSuppressed().length);
        }
    }

    @Test
    void 正常退出清理profile但不跟随其中的外部目录链接() throws Exception {
        Path parent = directory("scratch-parent");
        Path outside = directory("outside");
        Path sentinel = Files.writeString(outside.resolve("sentinel"), "preserved");
        BrowserWorkerScratch scratch = BrowserWorkerScratch.create(parent, BrowserWorkerScratch.identity(parent));
        Files.createSymbolicLink(scratch.root().resolve("outside-link"), outside);
        Files.writeString(
                Files.createDirectory(scratch.root().resolve("profile")).resolve("cache"), "fixture");

        if (secureDirectories(parent)) {
            scratch.cleanup();
            assertFalse(Files.exists(scratch.root()));
        } else {
            assertThrows(IOException.class, scratch::cleanup);
            assertTrue(Files.isDirectory(scratch.root()));
        }
        assertEquals("preserved", Files.readString(sentinel));
    }

    @Test
    void 分配父根被替换后拒绝启动且不在替换目录内创建文件() throws Exception {
        Path parent = directory("scratch-parent");
        AtomicInteger starts = new AtomicInteger();
        BrowserWorkerLauncher launcher =
                new BrowserWorkerLauncher(template(parent).withPrivateScratch(parent), command -> {
                    starts.incrementAndGet();
                    return new PendingProcess();
                });
        Files.move(parent, parent.resolveSibling("original-parent"));
        Files.createDirectory(parent);

        assertThrows(IOException.class, launcher::start);
        assertEquals(0, starts.get());
        try (var contents = Files.list(parent)) {
            assertEquals(0, contents.count());
        }
    }

    @Test
    void 清理时目录身份改变则保留替换目录和内容() throws Exception {
        Path parent = directory("scratch-parent");
        BrowserWorkerScratch scratch = BrowserWorkerScratch.create(parent, BrowserWorkerScratch.identity(parent));
        Files.move(scratch.root(), parent.resolve("original-instance"));
        Files.createDirectory(scratch.root());
        Path sentinel = Files.writeString(scratch.root().resolve("keep"), "replacement");

        assertThrows(IOException.class, scratch::cleanup);
        assertEquals("replacement", Files.readString(sentinel));
    }

    @Test
    void 普通命令不增加删除权限或分配临时目录() throws Exception {
        Path parent = directory("scratch-parent");
        SandboxedWorkerCommand ordinary = template(parent);
        PendingProcess process = new PendingProcess();
        AtomicReference<SandboxedWorkerCommand> started = new AtomicReference<>();
        BrowserWorkerLauncher launcher = new BrowserWorkerLauncher(ordinary, command -> {
            started.set(command);
            return process;
        });

        assertSame(process, launcher.start());
        assertSame(ordinary, started.get());
        assertTrue(started.get().privateScratch().isEmpty());
        process.finish();
    }

    @Test
    void 不一致的Java临时目录在启动前拒绝且回收空实例根() throws Exception {
        Path parent = directory("scratch-parent");
        SandboxedWorkerCommand original = template(parent);
        SandboxedWorkerCommand invalid = new SandboxedWorkerCommand(
                        original.id(),
                        List.of(original.argv().getFirst(), "-Djava.io.tmpdir=/unrelated", "-version"),
                        parent,
                        original.environment(),
                        original.readRoots(),
                        original.writeRoots(),
                        original.executableRoots(),
                        original.lifetime(),
                        original.limits(),
                        Optional.empty())
                .withPrivateScratch(parent);
        BrowserWorkerLauncher launcher = new BrowserWorkerLauncher(invalid, command -> {
            throw new AssertionError("invalid template must not launch");
        });

        assertThrows(IOException.class, launcher::start);
        try (var contents = Files.list(parent)) {
            assertEquals(0, contents.count());
        }
    }

    private SandboxedWorkerCommand template(Path parent) throws IOException {
        String executableName = System.getProperty("os.name", "")
                        .toLowerCase(java.util.Locale.ROOT)
                        .contains("windows")
                ? "java.exe"
                : "java";
        Path java =
                Path.of(System.getProperty("java.home"), "bin", executableName).toRealPath();
        return new SandboxedWorkerCommand(
                "browser-worker",
                List.of(java.toString(), "-Djava.io.tmpdir=" + parent, "-version"),
                parent,
                Map.of("TMPDIR", parent.toString(), "PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD", "1"),
                List.of(java.getParent().getParent()),
                List.of(parent),
                List.of(java),
                Duration.ofSeconds(10),
                LIMITS,
                Optional.empty());
    }

    private Path directory(String name) throws IOException {
        return Files.createDirectory(temporaryDirectory.resolve(name)).toRealPath();
    }

    private static void assertIsolated(SandboxedWorkerCommand template, SandboxedWorkerCommand command, Path parent) {
        Path root = command.workingDirectory();
        assertEquals(parent, root.getParent());
        assertEquals(List.of(root), command.writeRoots());
        assertEquals(root, command.privateScratch().orElseThrow().root());
        assertEquals(root.toString(), command.environment().get("TMPDIR"));
        assertTrue(command.argv().contains("-Djava.io.tmpdir=" + root));
        assertFalse(command.argv().contains("-Djava.io.tmpdir=" + parent));
        assertEquals(template.readRoots(), command.readRoots());
        assertEquals(template.executableRoots(), command.executableRoots());
        assertEquals(template.lifetime(), command.lifetime());
        assertEquals(template.limits(), command.limits());
    }

    private static void rendezvous(CyclicBarrier barrier) throws IOException {
        try {
            barrier.await(5, TimeUnit.SECONDS);
        } catch (Exception failure) {
            throw new IOException("concurrent launch rendezvous failed", failure);
        }
    }

    private static boolean secureDirectories(Path root) throws IOException {
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(root)) {
            return stream instanceof SecureDirectoryStream<Path>;
        }
    }

    private static void awaitDeleted(Path root) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
        while (Files.exists(root) && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertFalse(Files.exists(root));
    }

    private static final class PendingProcess extends Process {
        private final CompletableFuture<Process> exit = new CompletableFuture<>();
        private final ByteArrayOutputStream input = new ByteArrayOutputStream();

        void finish() {
            exit.complete(this);
        }

        @Override
        public OutputStream getOutputStream() {
            return input;
        }

        @Override
        public InputStream getInputStream() {
            return new ByteArrayInputStream(new byte[0]);
        }

        @Override
        public InputStream getErrorStream() {
            return new ByteArrayInputStream(new byte[0]);
        }

        @Override
        public int waitFor() throws InterruptedException {
            try {
                exit.get();
                return 0;
            } catch (java.util.concurrent.ExecutionException failure) {
                throw new AssertionError(failure);
            }
        }

        @Override
        public int exitValue() {
            if (!exit.isDone()) {
                throw new IllegalThreadStateException("running");
            }
            return 0;
        }

        @Override
        public void destroy() {
            finish();
        }

        @Override
        public Process destroyForcibly() {
            finish();
            return this;
        }

        @Override
        public boolean isAlive() {
            return !exit.isDone();
        }

        @Override
        public CompletableFuture<Process> onExit() {
            return exit;
        }
    }
}
