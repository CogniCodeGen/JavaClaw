package com.javaclaw.nativehost.coding;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SecureDirectoryStream;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Locale;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;

/** POSIX 目录描述符能力；所有名字只相对已持有目录解析，禁止退回绝对路径重试。 */
final class WorkspacePosixDirectory implements AutoCloseable {
    private final int descriptor;
    private SecureDirectoryStream<Path> stream;
    private boolean closed;

    private WorkspacePosixDirectory(int descriptor) {
        this.descriptor = descriptor;
    }

    static WorkspacePosixDirectory open(Path root) throws IOException {
        Path absolute = root.toAbsolutePath().normalize();
        int descriptor = Calls.open(-1, "/", true);
        try {
            for (int index = 0; index < absolute.getNameCount(); index++) {
                int next = Calls.open(descriptor, absolute.getName(index).toString(), true);
                Calls.close(descriptor);
                descriptor = next;
            }
        } catch (IOException | RuntimeException failure) {
            Calls.close(descriptor);
            throw failure;
        }
        return new WorkspacePosixDirectory(descriptor);
    }

    WorkspacePosixDirectory child(String name) throws IOException {
        requireOpen();
        return new WorkspacePosixDirectory(Calls.open(descriptor, name, true));
    }

    WorkspacePosixDirectory duplicate() throws IOException {
        return child(".");
    }

    SecureDirectoryStream<Path> stream() throws IOException {
        requireOpen();
        if (stream == null) {
            int readable = Calls.open(descriptor, ".", false);
            try {
                DirectoryStream<Path> opened = Files.newDirectoryStream(alias(readable));
                if (!(opened instanceof SecureDirectoryStream<Path> secure)) {
                    opened.close();
                    throw new IOException("Filesystem does not provide secure directory operations");
                }
                stream = secure;
            } finally {
                Calls.close(readable);
            }
        }
        return stream;
    }

    BasicFileAttributes attributes(String name) throws IOException {
        return stream()
                .getFileAttributeView(
                        Path.of(name), java.nio.file.attribute.BasicFileAttributeView.class, LinkOption.NOFOLLOW_LINKS)
                .readAttributes();
    }

    private static Path alias(int descriptor) {
        // 只有本类生成的仍存活描述符可出现在别名里；项目字符串仅作为单个最终名字追加。
        return Path.of(Calls.MAC ? "/dev/fd/" : "/proc/self/fd/", Integer.toString(descriptor));
    }

    void createDirectory(String name) throws IOException {
        requireOpen();
        Calls.mkdir(descriptor, name);
    }

    void moveNoReplace(String name, WorkspacePosixDirectory target, String targetName) throws IOException {
        requireOpen();
        target.requireOpen();
        Calls.move(descriptor, name, target.descriptor, targetName, Calls.MAC ? 4 : 1);
    }

    void exchange(String name, WorkspacePosixDirectory target, String targetName) throws IOException {
        requireOpen();
        target.requireOpen();
        Calls.move(descriptor, name, target.descriptor, targetName, 2);
    }

    private void requireOpen() throws IOException {
        if (closed) {
            throw new IOException("Workspace directory capability is closed");
        }
    }

    @Override
    public void close() throws IOException {
        if (!closed) {
            closed = true;
            try {
                if (stream != null) {
                    stream.close();
                }
            } finally {
                Calls.close(descriptor);
            }
        }
    }

    /** 原生原语缺失或文件系统不支持不可覆盖 rename 时拒绝提交，不采用检查后 rename。 */
    private static final class Calls {
        private static final boolean MAC =
                System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("mac");
        private static final Linker LINKER = Linker.nativeLinker();
        private static final MethodHandle OPEN = bind(
                "openat",
                FunctionDescriptor.of(JAVA_INT, JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT),
                Linker.Option.firstVariadicArg(3));
        private static final MethodHandle CLOSE = bind("close", FunctionDescriptor.of(JAVA_INT, JAVA_INT));
        private static final MethodHandle MKDIR =
                bind("mkdirat", FunctionDescriptor.of(JAVA_INT, JAVA_INT, ADDRESS, JAVA_INT));
        private static final MethodHandle MOVE = bind(
                MAC ? "renameatx_np" : "renameat2",
                FunctionDescriptor.of(JAVA_INT, JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, JAVA_INT));
        private static final MethodHandle ERRNO =
                bind(MAC ? "__error" : "__errno_location", FunctionDescriptor.of(ADDRESS));

        private Calls() {}

        private static MethodHandle bind(String name, FunctionDescriptor descriptor, Linker.Option... options) {
            return LINKER.downcallHandle(LINKER.defaultLookup().find(name).orElseThrow(), descriptor, options);
        }

        static int open(int parent, String name, boolean searchOnly) throws IOException {
            try (Arena arena = Arena.ofConfined()) {
                int flags = MAC ? 0x100000 | 0x100 | 0x1000000 : 0x10000 | 0x20000 | 0x80000;
                if (searchOnly) {
                    flags |= MAC ? 0x40000000 : 0x200000;
                }
                int fd = (int)
                        OPEN.invokeExact(parent < 0 ? (MAC ? -2 : -100) : parent, arena.allocateFrom(name), flags, 0);
                if (fd < 0) {
                    throw error("openat directory", name);
                }
                return fd;
            } catch (Throwable failure) {
                throw failure instanceof IOException io ? io : new IOException("openat failed", failure);
            }
        }

        static void mkdir(int parent, String name) throws IOException {
            try (Arena arena = Arena.ofConfined()) {
                if ((int) MKDIR.invokeExact(parent, arena.allocateFrom(name), 0700) != 0) {
                    throw error("mkdirat", name);
                }
            } catch (Throwable failure) {
                throw failure instanceof IOException io ? io : new IOException("mkdirat failed", failure);
            }
        }

        static void move(int parent, String name, int target, String targetName, int flags) throws IOException {
            try (Arena arena = Arena.ofConfined()) {
                if ((int) MOVE.invokeExact(
                                parent, arena.allocateFrom(name), target, arena.allocateFrom(targetName), flags)
                        != 0) {
                    throw error(flags == 2 ? "atomic exchange" : "rename without replacement", name);
                }
            } catch (Throwable failure) {
                throw failure instanceof IOException io ? io : new IOException("rename failed", failure);
            }
        }

        static void close(int descriptor) throws IOException {
            try {
                if ((int) CLOSE.invokeExact(descriptor) != 0) {
                    throw error("close", "directory");
                }
            } catch (Throwable failure) {
                throw failure instanceof IOException io ? io : new IOException("close failed", failure);
            }
        }

        private static IOException error(String operation, String name) throws Throwable {
            int errno = ((MemorySegment) ERRNO.invokeExact()).reinterpret(4).get(JAVA_INT, 0);
            if (errno == 2) {
                return new java.nio.file.NoSuchFileException(name);
            }
            if (errno == 17) {
                return new java.nio.file.FileAlreadyExistsException(name);
            }
            return new IOException(operation + " failed (errno=" + errno + "): " + name);
        }
    }
}
