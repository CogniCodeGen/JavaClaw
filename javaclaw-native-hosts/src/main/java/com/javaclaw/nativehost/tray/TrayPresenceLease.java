package com.javaclaw.nativehost.tray;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import com.javaclaw.nativehost.LocalRuntimeDirectories;
import com.javaclaw.nativehost.ManagedRuntimeDirectory;

/** 独占托盘 supervisor 并周期刷新不含用户数据的活动心跳。 */
public final class TrayPresenceLease implements AutoCloseable {
    private static final Duration HEARTBEAT_INTERVAL = Duration.ofSeconds(5);

    private final Path presenceFile;
    private final FileChannel lockChannel;
    private final FileLock lock;
    private final Clock clock;
    private final long processId;
    private final ScheduledExecutorService heartbeat;
    private final AtomicBoolean closed = new AtomicBoolean();

    /**
     * 在当前用户状态根创建独占 lease。
     *
     * @return 必须关闭的活动 lease
     * @throws IOException 目录、锁或心跳不可用
     */
    public static TrayPresenceLease acquireCurrentUser() throws IOException {
        ManagedRuntimeDirectory.prepare(LocalRuntimeDirectories.dataDirectory());
        Path presence = LocalRuntimeDirectories.dataDirectory().resolve("run/tray-v6.presence");
        return acquire(presence, Clock.systemUTC(), ProcessHandle.current().pid());
    }

    static TrayPresenceLease acquire(Path presenceFile, Clock clock, long processId) throws IOException {
        Path checked = Objects.requireNonNull(presenceFile, "presenceFile")
                .toAbsolutePath()
                .normalize();
        Files.createDirectories(checked.getParent());
        Path lockFile = checked.resolveSibling(checked.getFileName() + ".lock");
        FileChannel channel = FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        try {
            FileLock lock = channel.tryLock();
            if (lock == null) {
                throw new IOException("另一个 JavaClaw 托盘 supervisor 已在运行");
            }
            return new TrayPresenceLease(checked, channel, lock, clock, processId);
        } catch (OverlappingFileLockException failure) {
            channel.close();
            throw new IOException("另一个 JavaClaw 托盘 supervisor 已在运行", failure);
        } catch (IOException | RuntimeException failure) {
            channel.close();
            throw failure;
        }
    }

    private TrayPresenceLease(Path presenceFile, FileChannel lockChannel, FileLock lock, Clock clock, long processId)
            throws IOException {
        this.presenceFile = presenceFile;
        this.lockChannel = lockChannel;
        this.lock = lock;
        this.clock = Objects.requireNonNull(clock, "clock");
        if (processId < 1) {
            throw new IllegalArgumentException("processId must be positive");
        }
        this.processId = processId;
        writeHeartbeat();
        heartbeat = Executors.newSingleThreadScheduledExecutor(runnable ->
                Thread.ofPlatform().daemon(true).name("javaclaw-tray-heartbeat").unstarted(runnable));
        heartbeat.scheduleAtFixedRate(
                this::writeQuietly,
                HEARTBEAT_INTERVAL.toMillis(),
                HEARTBEAT_INTERVAL.toMillis(),
                TimeUnit.MILLISECONDS);
    }

    private void writeQuietly() {
        try {
            writeHeartbeat();
        } catch (IOException ignored) {
            // 探针会在 15 秒后 fail closed；心跳线程不伪造仍活动状态。
        }
    }

    private void writeHeartbeat() throws IOException {
        String content = "1\n" + processId + '\n' + clock.millis() + '\n';
        Path temporary = Files.createTempFile(presenceFile.getParent(), "tray-v6-", ".tmp");
        try {
            Files.writeString(
                    temporary,
                    content,
                    StandardCharsets.US_ASCII,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE);
            restrictOwner(temporary);
            try {
                Files.move(
                        temporary, presenceFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, presenceFile, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static void restrictOwner(Path file) throws IOException {
        try {
            Files.setPosixFilePermissions(
                    file,
                    Set.of(
                            java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                            java.nio.file.attribute.PosixFilePermission.OWNER_WRITE));
        } catch (UnsupportedOperationException ignored) {
            // Windows ACL 继承自当前用户私有状态目录。
        }
    }

    /** 删除心跳并释放跨进程独占锁；不会停止 App Server。 */
    @Override
    public void close() throws IOException {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        heartbeat.shutdownNow();
        IOException failure = null;
        failure = attempt(failure, () -> Files.deleteIfExists(presenceFile));
        failure = attempt(failure, lock::release);
        failure = attempt(failure, lockChannel::close);
        if (failure != null) {
            throw failure;
        }
    }

    private static IOException attempt(IOException failure, IoAction action) {
        try {
            action.run();
        } catch (IOException next) {
            if (failure == null) {
                return next;
            }
            failure.addSuppressed(next);
        }
        return failure;
    }

    @FunctionalInterface
    private interface IoAction {
        void run() throws IOException;
    }
}
