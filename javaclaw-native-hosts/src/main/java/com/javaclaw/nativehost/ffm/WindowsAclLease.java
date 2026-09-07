package com.javaclaw.nativehost.ffm;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;

/**
 * 当前用户的跨进程 ACL 生效区间锁。锁文件和 pending marker 位于受保护的固定 LocalAppData 子目录。 进程退出只释放内核锁，不清除 marker；后续 helper 必须拒绝接管，防止以不完整 DACL
 * 作为新基线。
 */
final class WindowsAclLease {
    private static final String MARKER = "pending-v1";
    private static final String PREFIX = "JAVACLAW-ACL-LEASE-V1\n";
    private final WindowsWorkspaceDirectory directory;
    private final WindowsFileHandle file;
    private boolean completed;

    private WindowsAclLease(WindowsWorkspaceDirectory directory, WindowsFileHandle file) {
        this.directory = directory;
        this.file = file;
    }

    static WindowsAclLease acquire(Path evidenceDirectory) throws IOException {
        return acquireIn(localRoot(), evidenceDirectory);
    }

    static WindowsAclLease acquireIn(Path root, Path evidenceDirectory) throws IOException {
        try {
            Files.createDirectory(root);
        } catch (FileAlreadyExistsException present) {
            // 既有目录仍经同句柄校验和受保护 DACL 验证，不能接受重解析点。
        }
        WindowsNetworkGuardNative.protectControlDirectory(root);
        WindowsWorkspaceDirectory directory = WindowsWorkspaceDirectory.open(root);
        WindowsFileHandle file = null;
        boolean locked = false;
        try {
            file = directory.openLeaseFile();
            lock(file);
            locked = true;
            rejectPending(directory, root);
            writePending(directory, evidenceDirectory);
            return new WindowsAclLease(directory, file);
        } catch (IOException | RuntimeException failure) {
            cleanup(directory, file, locked, failure);
            throw failure;
        }
    }

    private static Path localRoot() throws IOException {
        try (Arena arena = Arena.ofConfined()) {
            // FOLDERID_LocalAppData；来源为当前进程身份，不接受目标环境变量覆盖。
            byte[] identifier = {
                (byte) 0x85,
                0x27,
                (byte) 0xb3,
                (byte) 0xf1,
                (byte) 0xba,
                0x6f,
                (byte) 0xcf,
                0x4f,
                (byte) 0x9d,
                0x55,
                0x7b,
                (byte) 0x8e,
                0x7f,
                0x15,
                0x70,
                (byte) 0x91
            };
            MemorySegment output = arena.allocate(ADDRESS);
            int result = WindowsFileNative.call(
                            WindowsFileNative.backend().knownFolder,
                            arena.allocateFrom(JAVA_BYTE, identifier),
                            0,
                            MemorySegment.NULL,
                            output)
                    .number();
            if (result != 0) {
                throw WindowsSandboxNative.status("SHGetKnownFolderPath", result);
            }
            MemorySegment text = output.get(ADDRESS, 0);
            try {
                return Path.of(WindowsSandboxNative.readWide(text)).resolve("JavaClawAclLease-v6");
            } finally {
                WindowsFileNative.call(WindowsFileNative.backend().taskMemFree, text);
            }
        }
    }

    private static void lock(WindowsFileHandle file) throws IOException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment overlapped = arena.allocate(32, 8);
            do {
                var result = WindowsFileNative.call(
                        WindowsFileNative.backend().lockFile, file.address(), 3, 0, 1, 0, overlapped);
                if (result.number() != 0) {
                    return;
                }
                if (result.error() != 33) {
                    throw WindowsSandboxNative.error("LockFileEx", result.error());
                }
                pause();
            } while (System.nanoTime() - deadline < 0);
        }
        throw new IOException("Windows ACL lease is busy; no ACL changes were made");
    }

    private static void pause() throws IOException {
        try {
            Thread.sleep(25);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("Windows ACL lease wait interrupted before grant", interrupted);
        }
    }

    private static void rejectPending(WindowsWorkspaceDirectory directory, Path root) throws IOException {
        byte[] bytes;
        try (SeekableByteChannel channel = directory.openFile(MARKER, Set.of(StandardOpenOption.READ))) {
            bytes = Channels.newInputStream(channel).readNBytes(4097);
        } catch (NoSuchFileException absent) {
            return;
        }
        String value = new String(bytes, StandardCharsets.UTF_8);
        Path evidence = root;
        if (bytes.length <= 4096 && value.startsWith(PREFIX)) {
            try {
                Path previous = Path.of(value.substring(PREFIX.length()));
                if (previous.isAbsolute() && previous.normalize().equals(previous)) {
                    evidence = previous;
                }
            } catch (IllegalArgumentException invalid) {
                // 损坏 marker 同样阻止接管，只把可信全局控制目录作为审计入口。
            }
        }
        throw new WindowsSandbox.AclRestorationException(
                "Windows ACL lease has unconfirmed previous restoration", null, Optional.of(evidence));
    }

    private static void writePending(WindowsWorkspaceDirectory directory, Path evidence) throws IOException {
        byte[] bytes = (PREFIX + evidence).getBytes(StandardCharsets.UTF_8);
        if (bytes.length > 4096) {
            throw new IOException("Windows ACL evidence path exceeds marker capacity");
        }
        try (SeekableByteChannel channel =
                directory.openFile(MARKER, Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE))) {
            ByteBuffer source = ByteBuffer.wrap(bytes);
            while (source.hasRemaining()) {
                channel.write(source);
            }
            directory.force(channel);
        }
    }

    synchronized void complete(boolean restored) throws IOException {
        if (completed) {
            return;
        }
        completed = true;
        IOException failure = null;
        if (restored) {
            try {
                directory.deleteFile(MARKER);
            } catch (IOException current) {
                failure = current;
            }
        }
        if (failure == null) {
            failure = new IOException("Windows ACL lease cleanup failed");
            cleanup(directory, file, true, failure);
            if (failure.getSuppressed().length == 0) {
                return;
            }
        } else {
            cleanup(directory, file, true, failure);
        }
        throw failure;
    }

    private static void cleanup(
            WindowsWorkspaceDirectory directory, WindowsFileHandle file, boolean locked, Throwable failure) {
        if (file != null) {
            try (file) {
                if (locked) {
                    unlock(file);
                }
            } catch (IOException | RuntimeException cleanup) {
                failure.addSuppressed(cleanup);
            }
        }
        try {
            directory.close();
        } catch (IOException | RuntimeException cleanup) {
            failure.addSuppressed(cleanup);
        }
    }

    private static void unlock(WindowsFileHandle file) throws IOException {
        try (Arena arena = Arena.ofConfined()) {
            WindowsFileNative.requireSuccess(
                    WindowsFileNative.backend().unlockFile,
                    "UnlockFileEx",
                    file.address(),
                    0,
                    1,
                    0,
                    arena.allocate(32, 8));
        }
    }
}
