package com.javaclaw.server.preview;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.UserPrincipal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import com.javaclaw.api.CancellationSource;
import com.javaclaw.api.DocumentChunk;
import com.javaclaw.api.DocumentPreview;
import com.javaclaw.nativehost.coding.WorkspacePreviewSnapshot;

/**
 * 全进程快照磁盘预算与连接租约。预留覆盖 staging；创建中的 Worker 退出前不得释放占用。
 *
 * <p>短临界区只管理计数与句柄，文件复制和模型调用绝不在锁内执行。回收线程仅管理缓存，不执行业务定时任务。
 */
final class PreviewSnapshotCache implements AutoCloseable {
    static final long MAXIMUM_BYTES = 128L * 1024 * 1024;
    private static final Duration LEASE = Duration.ofMinutes(5);
    private final Path root;
    private final UserPrincipal owner;
    private final Clock clock;
    private final Map<String, Entry> entries = new HashMap<>();
    private final ScheduledExecutorService expiry;
    private final java.util.function.Consumer<Invalidation> notifications;
    private long reserved;
    private boolean closed;

    PreviewSnapshotCache(Path dataRoot, Clock clock) throws IOException {
        this(dataRoot, clock, ignored -> {});
    }

    PreviewSnapshotCache(Path dataRoot, Clock clock, java.util.function.Consumer<Invalidation> notifications)
            throws IOException {
        this.notifications = notifications;
        root = PreviewCacheFiles.instance(dataRoot);
        owner = Files.getOwner(root, LinkOption.NOFOLLOW_LINKS);
        this.clock = clock;
        expiry = Executors.newSingleThreadScheduledExecutor(
                Thread.ofPlatform().daemon().name("document-preview-expiry").factory());
        expiry.scheduleWithFixedDelay(this::expireQuietly, 30, 30, TimeUnit.SECONDS);
    }

    synchronized Entry reserve(String session, PreviewSources.Prepared source) throws IOException {
        if (closed) {
            throw new IOException("PREVIEW_CLOSED: 服务已关闭");
        }
        long capacity = source.details().size();
        if (capacity > MAXIMUM_BYTES - reserved
                || entries.values().stream()
                                .filter(entry -> entry.session.equals(session))
                                .count()
                        >= 8) {
            throw new IllegalArgumentException("PREVIEW_CAPACITY_EXCEEDED: 请先关闭已有预览");
        }
        String id = UUID.randomUUID().toString();
        Path directory = PreviewCacheFiles.directory(root.resolve(id), owner);
        Entry entry = new Entry(
                session, id, directory, capacity, source, clock.instant().plus(LEASE));
        entries.put(id, entry);
        reserved += capacity;
        return entry;
    }

    synchronized DocumentPreview complete(Entry entry, WorkspacePreviewSnapshot.Result result) throws IOException {
        entry.cancellation.throwIfCancelled();
        if (result.sizeBytes() > entry.capacity || entries.get(entry.id) != entry || closed) {
            throw new IOException("PREVIEW_CLOSED: 预览已释放或超出预留空间");
        }
        entry.result = result;
        // 复制完成后只保留来源身份；正文已经在磁盘，不让 Message 的临时 byte[] 随句柄长期驻留。
        entry.source = new PreviewSources.Prepared(
                entry.source.reference(),
                entry.source.details(),
                entry.source.access(),
                entry.source.relative(),
                java.util.Optional.empty());
        entry.creating = false;
        entry.expiresAt = clock.instant().plus(LEASE);
        return entry.preview();
    }

    synchronized Entry require(String session, String id) {
        Entry entry = entries.get(id);
        if (entry == null || !entry.session.equals(session)) {
            throw new SecurityException("PREVIEW_EXPIRED: 当前连接没有该预览句柄");
        }
        if (entry.creating || !clock.instant().isBefore(entry.expiresAt)) {
            throw new IllegalArgumentException("PREVIEW_EXPIRED: 请重新打开预览");
        }
        entry.cancellation.throwIfCancelled();
        return entry;
    }

    synchronized DocumentPreview renew(Entry entry) {
        if (entries.get(entry.id) != entry) {
            throw new IllegalArgumentException("PREVIEW_EXPIRED: 预览已回收");
        }
        entry.cancellation.throwIfCancelled();
        entry.expiresAt = clock.instant().plus(LEASE);
        return entry.preview();
    }

    DocumentChunk read(Entry entry, long offset, int maximum) throws IOException {
        synchronized (entry) {
            entry.cancellation.throwIfCancelled();
            long size = entry.result.sizeBytes();
            if (offset < 0 || offset > size || maximum < 1 || maximum > 256 * 1024) {
                throw new IllegalArgumentException("预览分块超出内容范围");
            }
            ByteBuffer buffer = ByteBuffer.allocate(Math.toIntExact(Math.min(maximum, size - offset)));
            try (FileChannel channel =
                    FileChannel.open(entry.content(), StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
                channel.position(offset);
                while (buffer.hasRemaining() && channel.read(buffer) >= 0) {
                    // 已有不可变版本按固定字节页读取，不跟随来源文件的后续变化。
                }
                if (buffer.hasRemaining()) {
                    throw new IOException("PREVIEW_CORRUPT: 快照内容变短");
                }
            }
            long next = offset + buffer.position();
            return new DocumentChunk(offset, buffer.array(), next, next == size, entry.result.digest());
        }
    }

    void closeHandle(String session, String id) throws IOException {
        invalidate(session, id, "CLOSED");
    }

    void invalidate(String session, String id, String reasonCode) throws IOException {
        Entry entry;
        boolean creating;
        boolean publish;
        synchronized (this) {
            entry = entries.get(id);
            if (entry == null) {
                return;
            }
            if (!entry.session.equals(session)) {
                throw new SecurityException("预览句柄不属于当前连接");
            }
            entry.cancellation.cancel("预览已关闭");
            creating = entry.creating;
            publish = !entry.invalidated;
            entry.invalidated = true;
        }
        if (publish) {
            notifications.accept(new Invalidation(session, id, reasonCode));
        }
        if (creating) {
            return;
        }
        release(entry);
    }

    void release(Entry entry) throws IOException {
        try {
            synchronized (entry) {
                PreviewCacheFiles.delete(entry.directory, owner);
            }
        } finally {
            synchronized (this) {
                // 调用方已经退出复制阶段；清理失败仍保留预算，但不能把遗留文件误判成仍在运行的 Worker。
                entry.creating = false;
                notifyAll();
            }
        }
        synchronized (this) {
            if (entries.remove(entry.id, entry)) {
                reserved -= entry.capacity;
                notifyAll();
            }
        }
    }

    void closeSession(String session) throws IOException {
        IOException failure = null;
        for (Entry entry : snapshot()) {
            if (entry.session.equals(session)) {
                failure = closeEntry(entry, failure);
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    private synchronized java.util.List<Entry> snapshot() {
        return new ArrayList<>(entries.values());
    }

    private void expireQuietly() {
        for (Entry entry : snapshot()) {
            if (!clock.instant().isBefore(entry.expiresAt)) {
                try {
                    invalidate(entry.session, entry.id, "EXPIRED");
                } catch (IOException ignored) {
                    // 保留预留和记录，下次继续回收；清理失败不虚报空间已经释放。
                }
            }
        }
    }

    @Override
    public void close() throws IOException {
        synchronized (this) {
            closed = true;
        }
        expiry.shutdownNow();
        IOException failure = null;
        for (Entry entry : snapshot()) {
            failure = closeEntry(entry, failure);
        }
        try {
            awaitCreators();
        } catch (IOException waitingFailure) {
            failure = accumulate(failure, waitingFailure);
        }
        if (failure != null) {
            throw failure;
        }
    }

    private IOException closeEntry(Entry entry, IOException failure) {
        try {
            closeHandle(entry.session, entry.id);
            return failure;
        } catch (IOException cleanupFailure) {
            return accumulate(failure, cleanupFailure);
        }
    }

    private static IOException accumulate(IOException prior, IOException failure) {
        if (prior == null) {
            return failure;
        }
        prior.addSuppressed(failure);
        return prior;
    }

    private synchronized void awaitCreators() throws IOException {
        long deadline = System.nanoTime() + Duration.ofSeconds(35).toNanos();
        while (entries.values().stream().anyMatch(entry -> entry.creating)) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) {
                throw new IOException("预览 Worker 尚未退出，缓存预留仍保留");
            }
            try {
                TimeUnit.NANOSECONDS.timedWait(this, remaining);
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                throw new IOException("等待预览 Worker 退出时被中断", failure);
            }
        }
    }

    static final class Entry {
        boolean invalidated;
        final String session;
        final String id;
        final Path directory;
        final long capacity;
        volatile PreviewSources.Prepared source;
        final CancellationSource cancellation = new CancellationSource();
        volatile Instant expiresAt;
        volatile boolean creating = true;
        volatile WorkspacePreviewSnapshot.Result result;

        Entry(
                String session,
                String id,
                Path directory,
                long capacity,
                PreviewSources.Prepared source,
                Instant expiresAt) {
            this.session = session;
            this.id = id;
            this.directory = directory;
            this.capacity = capacity;
            this.source = source;
            this.expiresAt = expiresAt;
        }

        Path content() {
            return directory.resolve("content");
        }

        DocumentPreview preview() {
            var details = source.details();
            return new DocumentPreview(
                    id,
                    source.reference().workspaceId(),
                    details.name(),
                    details.mediaType(),
                    result.sizeBytes(),
                    result.digest(),
                    details.origin(),
                    expiresAt,
                    details.line(),
                    details.expectedDigest()
                            .filter(expected -> !expected.equals(result.digest()))
                            .isPresent());
        }
    }

    record Invalidation(String session, String handleId, String reasonCode) {}
}
