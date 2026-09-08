package com.javaclaw.server.preview;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.javaclaw.api.AttachmentRef;
import com.javaclaw.api.DocumentPreview;
import com.javaclaw.api.DocumentReference;
import com.javaclaw.api.WorkspaceId;
import com.javaclaw.nativehost.coding.WorkspacePreviewSnapshot;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class PreviewSnapshotCacheTest {
    private static final String DIGEST = "a".repeat(64);

    @TempDir
    Path temporary;

    @Test
    void 分块句柄隔离且续租不改变不可变版本() throws Exception {
        var clock = new MutableClock();
        try (var cache = new PreviewSnapshotCache(Files.createDirectory(temporary.resolve("data-v6")), clock)) {
            var entry = cache.reserve("first", source(6));
            Files.write(entry.content(), new byte[] {1, 2, 3, 4, 5, 6});
            var preview = cache.complete(entry, new WorkspacePreviewSnapshot.Result(6, DIGEST));
            assertThrows(SecurityException.class, () -> cache.require("second", entry.id));
            assertThrows(SecurityException.class, () -> cache.closeHandle("second", entry.id));
            var chunk = cache.read(cache.require("first", entry.id), 2, 3);
            assertArrayEquals(new byte[] {3, 4, 5}, chunk.content());
            assertEquals(5, chunk.nextOffsetBytes());
            clock.now = clock.now.plusSeconds(240);
            assertTrue(cache.renew(entry).expiresAt().isAfter(preview.expiresAt()));
            clock.now = clock.now.plusSeconds(301);
            assertThrows(IllegalArgumentException.class, () -> cache.require("first", entry.id));
            cache.closeHandle("first", entry.id);
            assertThrows(SecurityException.class, () -> cache.require("first", entry.id));
        }
    }

    @Test
    void 创建中的快照计入全局预算且取消后等待复制者退出再释放() throws Exception {
        try (var cache =
                new PreviewSnapshotCache(Files.createDirectory(temporary.resolve("data-v6")), Clock.systemUTC())) {
            var first = cache.reserve("first", source(64L * 1024 * 1024));
            var second = cache.reserve("second", source(64L * 1024 * 1024));
            assertThrows(IllegalArgumentException.class, () -> cache.reserve("third", source(1)));
            cache.closeSession("first");
            assertTrue(first.cancellation.isCancelled());
            assertThrows(IllegalArgumentException.class, () -> cache.reserve("third", source(1)));
            cache.release(first);
            var third = cache.reserve("third", source(1));
            cache.release(second);
            cache.release(third);
        }
    }

    @Test
    void 每连接最多八个句柄且空文件也受句柄预算限制() throws Exception {
        try (var cache =
                new PreviewSnapshotCache(Files.createDirectory(temporary.resolve("data-v6")), Clock.systemUTC())) {
            for (int count = 0; count < 8; count++) {
                var entry = cache.reserve("first", source(0));
                Files.write(entry.content(), new byte[0]);
                cache.complete(entry, new WorkspacePreviewSnapshot.Result(0, DIGEST));
            }
            assertThrows(IllegalArgumentException.class, () -> cache.reserve("first", source(0)));
            cache.closeSession("first");
            var entry = cache.reserve("first", source(0));
            cache.release(entry);
        }
    }

    @Test
    void 快照读拒绝非法边界和已损坏文件且关闭通知只发送一次() throws Exception {
        var notifications = new ArrayList<PreviewSnapshotCache.Invalidation>();
        try (var cache = new PreviewSnapshotCache(
                Files.createDirectory(temporary.resolve("data-v6")), Clock.systemUTC(), notifications::add)) {
            var entry = cache.reserve("first", source(3));
            assertThrows(IllegalArgumentException.class, () -> cache.require("first", entry.id));
            Files.write(entry.content(), new byte[] {1, 2, 3});
            cache.complete(entry, new WorkspacePreviewSnapshot.Result(3, DIGEST));
            assertThrows(IllegalArgumentException.class, () -> cache.read(entry, -1, 1));
            assertThrows(IllegalArgumentException.class, () -> cache.read(entry, 4, 1));
            assertThrows(IllegalArgumentException.class, () -> cache.read(entry, 0, 0));
            assertThrows(IllegalArgumentException.class, () -> cache.read(entry, 0, 256 * 1024 + 1));
            assertTrue(cache.read(entry, 3, 1).complete());
            Files.write(entry.content(), new byte[] {1});
            assertThrows(IOException.class, () -> cache.read(entry, 0, 3));
            cache.invalidate("first", entry.id, "CORRUPT");
            cache.closeHandle("first", entry.id);
            assertEquals(1, notifications.size());
            assertEquals("CORRUPT", notifications.getFirst().reasonCode());
            assertThrows(IllegalArgumentException.class, () -> cache.renew(entry));
        }
    }

    @Test
    void 单个目录回收失败不阻止同连接其余句柄关闭且保留失败预算() throws Exception {
        assumeTrue(temporary.getFileSystem().supportedFileAttributeViews().contains("posix"));
        var cache = new PreviewSnapshotCache(Files.createDirectory(temporary.resolve("data-v6")), Clock.systemUTC());
        var first = completed(cache, "first");
        var second = completed(cache, "first");
        var third = completed(cache, "first");
        var other = completed(cache, "other");
        Path firstLink = Files.createSymbolicLink(first.directory.resolve("guard"), temporary);
        Path secondLink = Files.createSymbolicLink(second.directory.resolve("guard"), temporary);
        try {
            IOException failure = assertThrows(IOException.class, () -> cache.closeSession("first"));
            assertEquals(1, failure.getSuppressed().length);
            assertTrue(first.cancellation.isCancelled());
            assertTrue(second.cancellation.isCancelled());
            assertTrue(third.cancellation.isCancelled());
            assertFalse(Files.exists(third.directory));
            assertFalse(other.cancellation.isCancelled());
        } finally {
            Files.deleteIfExists(firstLink);
            Files.deleteIfExists(secondLink);
            cache.close();
        }
    }

    @Test
    void 关闭聚合回收失败后仍取消其他句柄并等待复制者退出() throws Exception {
        assumeTrue(temporary.getFileSystem().supportedFileAttributeViews().contains("posix"));
        var cancelled = new CountDownLatch(2);
        var cache = new PreviewSnapshotCache(
                Files.createDirectory(temporary.resolve("data-v6")),
                Clock.systemUTC(),
                ignored -> cancelled.countDown());
        var bad = completed(cache, "first");
        var active = cache.reserve("other", source(1));
        Path link = Files.createSymbolicLink(bad.directory.resolve("guard"), temporary);
        var failure = new AtomicReference<IOException>();
        var returned = new CountDownLatch(1);
        Thread closing = Thread.ofVirtual().start(() -> {
            try {
                cache.close();
            } catch (IOException error) {
                failure.set(error);
            } finally {
                returned.countDown();
            }
        });
        try {
            assertTrue(cancelled.await(2, TimeUnit.SECONDS));
            assertTrue(active.cancellation.isCancelled());
            assertTrue(bad.cancellation.isCancelled());
            assertFalse(returned.await(100, TimeUnit.MILLISECONDS));
            cache.release(active);
            assertTrue(returned.await(2, TimeUnit.SECONDS));
            assertTrue(failure.get() != null);
        } finally {
            cache.release(active);
            Files.deleteIfExists(link);
            closing.join(3000);
            cache.close();
        }
    }

    private PreviewSnapshotCache.Entry completed(PreviewSnapshotCache cache, String session) throws Exception {
        var entry = cache.reserve(session, source(0));
        Files.write(entry.content(), new byte[0]);
        cache.complete(entry, new WorkspacePreviewSnapshot.Result(0, DIGEST));
        return entry;
    }

    @Test
    void 复制结果超出预留预算不进入可读状态() throws Exception {
        var cache = new PreviewSnapshotCache(Files.createDirectory(temporary.resolve("data-v6")), Clock.systemUTC());
        try (cache) {
            var entry = cache.reserve("first", source(1));
            Files.write(entry.content(), new byte[] {1, 2});
            assertThrows(
                    IOException.class, () -> cache.complete(entry, new WorkspacePreviewSnapshot.Result(2, DIGEST)));
            assertThrows(IllegalArgumentException.class, () -> cache.require("first", entry.id));
            cache.release(entry);
        }
        assertThrows(IOException.class, () -> cache.reserve("first", source(0)));
    }

    private PreviewSources.Prepared source(long size) {
        return new PreviewSources.Prepared(
                DocumentReference.attachment(
                        WorkspaceId.random(), new AttachmentRef(DIGEST, "text/plain", "test.txt", size)),
                new PreviewSources.Details(
                        "test.txt",
                        "text/plain",
                        size,
                        DocumentPreview.Origin.REFERENCED_VERSION,
                        Optional.of(DIGEST),
                        Optional.empty()),
                Optional.empty(),
                "",
                Optional.empty());
    }

    private static final class MutableClock extends Clock {
        Instant now = Instant.parse("2026-09-07T00:00:00Z");

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
