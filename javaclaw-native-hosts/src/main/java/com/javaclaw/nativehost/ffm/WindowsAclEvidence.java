package com.javaclaw.nativehost.ffm;

import java.io.DataOutputStream;
import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.channels.Channels;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static java.lang.foreign.ValueLayout.JAVA_INT;

/**
 * 可信 helper 的 ACL 证据作用域。调用方先保护目录，禁止目标 AppContainer 读写，再绑定当前线程。 每次授权前持久保存原始 DACL 与同句柄文件身份；Snapshot
 * 独立持有目录引用，允许其他线程恢复后记录结果。 此类型只写审计凭据，绝不按保存的展示路径恢复权限。
 */
public final class WindowsAclEvidence implements AutoCloseable {
    private static final ThreadLocal<WindowsAclEvidence> CURRENT = new ThreadLocal<>();
    private final Thread owner = Thread.currentThread();
    private final Shared writer;
    private boolean closed;

    private WindowsAclEvidence(Shared writer) {
        this.writer = writer;
    }

    /** 绑定已经由宿主保护的现有绝对控制目录，并固定其句柄；同一线程不允许嵌套。 必须在创建线程关闭。关闭作用域不提前释放仍被未恢复 Snapshot 使用的目录。 */
    public static WindowsAclEvidence open(Path directory) throws IOException {
        if (CURRENT.get() != null) {
            throw new IOException("Windows ACL evidence scope is already active");
        }
        return bind(directory, WindowsAclLease.acquire(directory));
    }

    static WindowsAclEvidence bind(Path directory, WindowsAclLease lease) throws IOException {
        try {
            if (CURRENT.get() != null) {
                throw new IOException("Windows ACL evidence scope is already active");
            }
            WindowsAclEvidence scope =
                    new WindowsAclEvidence(new Shared(WindowsWorkspaceDirectory.open(directory), lease));
            CURRENT.set(scope);
            return scope;
        } catch (IOException | RuntimeException failure) {
            try {
                lease.complete(true);
            } catch (IOException cleanup) {
                failure.addSuppressed(cleanup);
            }
            throw failure;
        }
    }

    static boolean terminationRequested() throws IOException {
        WindowsAclEvidence scope = CURRENT.get();
        if (scope == null || scope.closed) {
            return false;
        }
        try {
            return scope.writer.directory.attributes("terminate-request").isRegularFile();
        } catch (NoSuchFileException absent) {
            return false;
        }
    }

    static Ticket capture(WindowsFileHandle handle, Path path, String sddl, boolean protectedDacl) throws IOException {
        WindowsAclEvidence scope = CURRENT.get();
        if (scope == null || scope.closed) {
            throw new IOException("Windows ACL grant requires a trusted evidence scope");
        }
        Identity identity = identity(handle);
        String key = "acl-" + UUID.randomUUID();
        Shared writer = scope.writer;
        writer.retain();
        try {
            writer.write(key + ".baseline", output -> {
                output.writeInt(0x4a434142);
                output.writeInt(1);
                text(output, path.toString());
                output.writeInt(identity.volume());
                output.writeLong(identity.fileId());
                output.writeBoolean(protectedDacl);
                text(output, sddl);
            });
            return new Ticket(writer, key);
        } catch (IOException | RuntimeException failure) {
            try {
                writer.release();
            } catch (IOException cleanup) {
                failure.addSuppressed(cleanup);
            }
            throw failure;
        }
    }

    private static Identity identity(WindowsFileHandle handle) throws IOException {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment information = WindowsFileHandle.information(handle.address(), arena);
            long fileId = ((long) information.get(JAVA_INT, 44) << 32)
                    | Integer.toUnsignedLong(information.get(JAVA_INT, 48));
            return new Identity(information.get(JAVA_INT, 28), fileId);
        }
    }

    private static void text(DataOutputStream output, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > 1024 * 1024) {
            throw new IOException("Windows ACL evidence text exceeds byte limit");
        }
        output.writeInt(bytes.length);
        output.write(bytes);
    }

    /** 解除当前线程绑定并释放作用域引用；已有 Snapshot 仍拥有其恢复证据的写入权。 */
    @Override
    public void close() throws IOException {
        if (Thread.currentThread() != owner) {
            throw new IOException("Windows ACL evidence scope must close on its owner thread");
        }
        if (!closed) {
            closed = true;
            CURRENT.remove();
            writer.release();
        }
    }

    /** 每个 Snapshot 独占一张凭据；即使证据写入失败也释放 pin，由调用方传播安全失败。 */
    static final class Ticket {
        private final Shared writer;
        private final String key;
        private final AtomicBoolean finished = new AtomicBoolean();

        private Ticket(Shared writer, String key) {
            this.writer = writer;
            this.key = key;
        }

        void finish(Throwable failure) throws IOException {
            if (!finished.compareAndSet(false, true)) {
                return;
            }
            if (failure != null) {
                writer.keepFailure();
            }
            try {
                writer.write(key + ".restoration", output -> {
                    output.writeInt(0x4a434152);
                    output.writeInt(1);
                    output.writeBoolean(failure == null);
                    text(output, failure == null ? "" : failure.getClass().getName() + ": " + failure.getMessage());
                });
            } finally {
                writer.release();
            }
        }
    }

    private static final class Shared {
        private final WindowsWorkspaceDirectory directory;
        private final WindowsAclLease lease;
        private int references = 1;
        private final List<String> created = new ArrayList<>();
        private boolean failed;

        private Shared(WindowsWorkspaceDirectory directory, WindowsAclLease lease) {
            this.directory = directory;
            this.lease = lease;
        }

        private synchronized void retain() throws IOException {
            if (references == 0) {
                throw new IOException("Windows ACL evidence directory is closed");
            }
            references++;
        }

        private synchronized void release() throws IOException {
            if (--references != 0) {
                return;
            }
            IOException failure = null;
            try {
                // 成功恢复的凭据必须保留到 pending marker 已清除，崩溃不能留下无基线的未决租约。
                lease.complete(!failed);
            } catch (IOException current) {
                failure = current;
            }
            try {
                if (!failed && failure == null) {
                    for (String name : created) {
                        directory.deleteFile(name);
                    }
                }
            } catch (IOException current) {
                failure = append(failure, current);
            }
            try {
                directory.close();
            } catch (IOException current) {
                failure = append(failure, current);
            }
            if (failure != null) {
                throw failure;
            }
        }

        private static IOException append(IOException failure, IOException current) {
            if (failure == null) {
                return current;
            }
            failure.addSuppressed(current);
            return failure;
        }

        private synchronized void keepFailure() {
            failed = true;
        }

        private synchronized void write(String name, Encoder encoder) throws IOException {
            try (SeekableByteChannel channel =
                    directory.openFile(name, Set.of(StandardOpenOption.WRITE, StandardOpenOption.CREATE_NEW))) {
                created.add(name);
                DataOutputStream output = new DataOutputStream(Channels.newOutputStream(channel));
                encoder.write(output);
                output.flush();
                directory.force(channel);
            } catch (IOException | RuntimeException failure) {
                failed = true;
                throw failure;
            }
        }
    }

    @FunctionalInterface
    private interface Encoder {
        void write(DataOutputStream output) throws IOException;
    }

    private record Identity(int volume, long fileId) {}
}
