package com.javaclaw.server.security;

import java.io.IOException;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommandProxyAuthorityBoundaryTest {
    @TempDir
    Path directory;

    @Test
    void remoteEofDoesNotInterruptAuthorityCodeOrCloseItsSharedFileChannel() throws Exception {
        Path evidence = Files.writeString(directory.resolve("authority.bin"), "authority state");
        try (var channel = FileChannel.open(evidence, StandardOpenOption.READ);
                var fixture = new CommandProxyFixture()) {
            var boundary = new AuthorityBoundary(channel);
            var lease = fixture.lease(1024, 2, Duration.ofSeconds(10), boundary::check);
            try (Socket client = fixture.connect(lease, "repo.example:443");
                    Socket peer = fixture.server.accept()) {
                assertTrue(CommandProxyFixture.response(client).startsWith("HTTP/1.1 200 "));
                assertTrue(boundary.entered.await(3, TimeUnit.SECONDS));
                // 上游正常 EOF 时，上传线程可能恰好在读取共享权限数据库，而非等待 Socket。
                peer.shutdownOutput();
                CommandProxyFixture.closed(client);
                assertFalse(boundary.interrupted.await(300, TimeUnit.MILLISECONDS));
            } finally {
                boundary.release.countDown();
            }
            assertTrue(boundary.finished.await(3, TimeUnit.SECONDS));
            assertNull(boundary.failure.get());
            assertTrue(channel.isOpen());
            assertTrue(lease.active());
            CommandProxyFixture.await(() -> fixture.opened.stream().allMatch(Socket::isClosed));
        }
    }

    private static final class AuthorityBoundary {
        private final FileChannel channel;
        private final AtomicBoolean selected = new AtomicBoolean();
        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private final CountDownLatch interrupted = new CountDownLatch(1);
        private final CountDownLatch finished = new CountDownLatch(1);
        private final AtomicReference<Throwable> failure = new AtomicReference<>();

        private AuthorityBoundary(FileChannel channel) {
            this.channel = channel;
        }

        private void check() throws IOException {
            if (!Thread.currentThread().getName().equals("javaclaw-connect-upload")
                    || !selected.compareAndSet(false, true)) {
                return;
            }
            entered.countDown();
            try {
                awaitRelease();
                // FileChannel 与 H2 的底层通道一样，线程中断可能关闭被多个事务共享的资源。
                channel.read(ByteBuffer.allocate(1), 0);
            } catch (IOException problem) {
                failure.set(problem);
                throw problem;
            } finally {
                finished.countDown();
            }
        }

        private void awaitRelease() throws IOException {
            try {
                if (!release.await(5, TimeUnit.SECONDS)) {
                    throw new IOException("authority test was not released");
                }
            } catch (InterruptedException stopped) {
                interrupted.countDown();
                Thread.currentThread().interrupt();
            }
        }
    }
}
