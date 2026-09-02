package com.javaclaw.nativehost.ffm;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

import com.javaclaw.api.ResourceLimits;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;
import static java.lang.foreign.ValueLayout.JAVA_SHORT;

/** POSIX pseudo-terminal owner used only inside the dedicated sandbox launcher JVM. */
public final class PosixPty implements AutoCloseable {
    private static final MethodHandle POSIX_OPENPT = lookup("posix_openpt", FunctionDescriptor.of(JAVA_INT, JAVA_INT));
    private static final MethodHandle GRANTPT = lookup("grantpt", FunctionDescriptor.of(JAVA_INT, JAVA_INT));
    private static final MethodHandle UNLOCKPT = lookup("unlockpt", FunctionDescriptor.of(JAVA_INT, JAVA_INT));
    private static final MethodHandle PTSNAME = lookup("ptsname", FunctionDescriptor.of(ADDRESS, JAVA_INT));
    private static final MethodHandle OPEN = lookup("open", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT));
    private static final MethodHandle READ =
            lookup("read", FunctionDescriptor.of(JAVA_LONG, JAVA_INT, ADDRESS, JAVA_LONG));
    private static final MethodHandle WRITE =
            lookup("write", FunctionDescriptor.of(JAVA_LONG, JAVA_INT, ADDRESS, JAVA_LONG));
    private static final MethodHandle CLOSE = lookup("close", FunctionDescriptor.of(JAVA_INT, JAVA_INT));
    private static final MethodHandle SETSID = lookup("setsid", FunctionDescriptor.of(JAVA_INT));
    private static final MethodHandle GETPID = lookup("getpid", FunctionDescriptor.of(JAVA_INT));
    private static final MethodHandle GETSID = lookup("getsid", FunctionDescriptor.of(JAVA_INT, JAVA_INT));
    private static final MethodHandle GETPGRP = lookup("getpgrp", FunctionDescriptor.of(JAVA_INT));
    private static final MethodHandle TCSETPGRP =
            lookup("tcsetpgrp", FunctionDescriptor.of(JAVA_INT, JAVA_INT, JAVA_INT));
    private static final MethodHandle IOCTL_POINTER =
            variadic("ioctl", FunctionDescriptor.of(JAVA_INT, JAVA_INT, JAVA_LONG, ADDRESS));
    private static final MethodHandle IOCTL_LONG =
            variadic("ioctl", FunctionDescriptor.of(JAVA_INT, JAVA_INT, JAVA_LONG, JAVA_LONG));

    private final int master;
    private final Path slave;
    private final AtomicBoolean closed = new AtomicBoolean();

    private PosixPty(int master, Path slave) {
        this.master = master;
        this.slave = slave;
    }

    /** 检查当前平台及 PTY 必需原生符号是否可用；不创建终端，也不代替实际打开时的权限校验。 */
    public static boolean isSupported() {
        return platform() != null
                && java.util.stream.Stream.of(
                                POSIX_OPENPT,
                                GRANTPT,
                                UNLOCKPT,
                                PTSNAME,
                                OPEN,
                                READ,
                                WRITE,
                                CLOSE,
                                SETSID,
                                GETPGRP,
                                TCSETPGRP,
                                GETPID,
                                GETSID,
                                IOCTL_POINTER,
                                IOCTL_LONG)
                        .allMatch(Objects::nonNull);
    }

    /** 创建 PTY 并设置初始尺寸，columns 范围 20–1000、rows 范围 5–1000；调用方必须 close 归还 master FD，创建失败时回收已分配 FD。 */
    public static PosixPty open(int columns, int rows) {
        Platform platform = requirePlatform();
        if (!isSupported()) {
            throw new UnsupportedOperationException("POSIX pseudo-terminals are unavailable");
        }
        validateDimensions(columns, rows);
        int descriptor = -1;
        try {
            descriptor = (int) POSIX_OPENPT.invoke(2 | platform.noControllingTerminalFlag());
            if (descriptor < 0) {
                throw new IllegalStateException("posix_openpt failed");
            }
            if ((int) GRANTPT.invoke(descriptor) != 0) {
                throw new IllegalStateException("grantpt failed");
            }
            if ((int) UNLOCKPT.invoke(descriptor) != 0) {
                throw new IllegalStateException("unlockpt failed");
            }
            MemorySegment name = (MemorySegment) PTSNAME.invoke(descriptor);
            if (name.equals(MemorySegment.NULL)) {
                throw new IllegalStateException("ptsname failed");
            }
            String value = name.reinterpret(4_096).getString(0);
            Path slave = Path.of(value).toAbsolutePath().normalize();
            if (!slave.startsWith(Path.of("/dev")) || value.indexOf('\0') >= 0) {
                throw new SecurityException("PTY slave path is outside /dev");
            }
            PosixPty result = new PosixPty(descriptor, slave);
            result.resize(columns, rows);
            return result;
        } catch (RuntimeException failure) {
            closeAfterFailure(descriptor);
            throw failure;
        } catch (Throwable failure) {
            closeAfterFailure(descriptor);
            throw new IllegalStateException("cannot create POSIX pseudo-terminal", failure);
        }
    }

    /** 返回 OS 分配且已验证位于 /dev 下的 slave 路径；路径本身不转移 master FD 的所有权。 */
    public Path slavePath() {
        return slave;
    }

    /** Returns null on EOF or when the final slave descriptor has closed. */
    public byte[] read(int maximumBytes) {
        requireOpen();
        if (maximumBytes < 1 || maximumBytes > 1024 * 1024) {
            throw new IllegalArgumentException("PTY read bound is invalid");
        }
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment buffer = arena.allocate(maximumBytes);
            long count = (long) READ.invoke(master, buffer, (long) maximumBytes);
            if (count <= 0) {
                return null;
            }
            return buffer.asSlice(0, count).toArray(JAVA_BYTE);
        } catch (RuntimeException failure) {
            throw failure;
        } catch (Throwable failure) {
            throw new IllegalStateException("cannot read POSIX PTY", failure);
        }
    }

    /** 向仍打开的 master 写完非空字节数组；空数组不操作，原生写入无进展时抛出 IllegalStateException。 */
    public void write(byte[] value) {
        Objects.requireNonNull(value, "value");
        requireOpen();
        if (value.length == 0) {
            return;
        }
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment buffer = arena.allocateFrom(JAVA_BYTE, value);
            long offset = 0;
            while (offset < value.length) {
                long count = (long) WRITE.invoke(master, buffer.asSlice(offset), value.length - offset);
                if (count <= 0) {
                    throw new IllegalStateException("PTY write failed");
                }
                offset += count;
            }
        } catch (RuntimeException failure) {
            throw failure;
        } catch (Throwable failure) {
            throw new IllegalStateException("cannot write POSIX PTY", failure);
        }
    }

    /** 向终端发送 EOT 字节而不关闭 master，以保留待读输出；是否解释为 EOF 取决于 slave 的终端模式，并非任意 raw 模式的半关闭。 */
    public void closeInput() {
        // 保持 master 打开以继续读取输出；EOT 仅由终端行规程解释，不能假设 raw 模式也会产生 EOF。
        write(new byte[] {4});
    }

    /** 更新终端字符尺寸；columns 为 20–1000、rows 为 5–1000，内核会向已接管终端的前台进程组发送 SIGWINCH。 */
    public void resize(int columns, int rows) {
        Platform platform = requirePlatform();
        requireOpen();
        validateDimensions(columns, rows);
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment size = windowSize(arena, columns, rows);
            int slaveDescriptor = openSlave(arena, platform);
            try {
                if ((int) IOCTL_POINTER.invoke(slaveDescriptor, platform.setWindowSize(), size) != 0) {
                    throw new IllegalStateException("TIOCSWINSZ failed");
                }
            } finally {
                CLOSE.invoke(slaveDescriptor);
            }
        } catch (RuntimeException failure) {
            throw failure;
        } catch (Throwable failure) {
            throw new IllegalStateException("cannot resize POSIX PTY", failure);
        }
    }

    /**
     * 在 helper 的标准描述符已连接 slave 后建立 controlling terminal，并在 exec 前施加地址空间限制。
     *
     * @param target OS Sandbox backend argv
     * @param columns 初始列数
     * @param rows 初始行数
     * @param limits 目标资源上限
     */
    public static void attachControllingTerminalAndExec(
            List<String> target, int columns, int rows, ResourceLimits limits) {
        Platform platform = requirePlatform();
        if (!isSupported()) {
            throw new UnsupportedOperationException("POSIX pseudo-terminals are unavailable");
        }
        validateDimensions(columns, rows);
        try {
            int session = (int) SETSID.invoke();
            if (session < 0 && !isOwnSessionLeader()) {
                throw new IllegalStateException("setsid failed");
            }
            if ((int) IOCTL_LONG.invoke(0, platform.setControllingTerminal(), 0L) != 0) {
                throw new IllegalStateException("TIOCSCTTY failed");
            }
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment size = windowSize(arena, columns, rows);
                if ((int) IOCTL_POINTER.invoke(0, platform.setWindowSize(), size) != 0) {
                    throw new IllegalStateException("initial TIOCSWINSZ failed");
                }
            }
            int processGroup = (int) GETPGRP.invoke();
            if (processGroup < 1 || (int) TCSETPGRP.invoke(0, processGroup) != 0) {
                throw new IllegalStateException("tcsetpgrp failed");
            }
            NativeResourceLimits.applyAddressSpaceLimitAndExec(target, limits);
        } catch (RuntimeException failure) {
            throw failure;
        } catch (Throwable failure) {
            throw new IllegalStateException("cannot attach controlling terminal", failure);
        }
    }

    private static boolean isOwnSessionLeader() throws Throwable {
        int process = (int) GETPID.invoke();
        int session = (int) GETSID.invoke(0);
        return process > 0 && process == session;
    }

    private static MemorySegment windowSize(Arena arena, int columns, int rows) {
        MemorySegment size = arena.allocate(8, 2);
        size.set(JAVA_SHORT, 0, (short) rows);
        size.set(JAVA_SHORT, 2, (short) columns);
        size.set(JAVA_SHORT, 4, (short) 0);
        size.set(JAVA_SHORT, 6, (short) 0);
        return size;
    }

    private static void validateDimensions(int columns, int rows) {
        if (columns < 20 || columns > 1_000 || rows < 5 || rows > 1_000) {
            throw new IllegalArgumentException("PTY dimensions are invalid");
        }
    }

    @Override
    public void close() {
        // master 由本实例独占；原子标志防止重复 close 意外关闭 OS 已复用的描述符。
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        try {
            CLOSE.invoke(master);
        } catch (Throwable ignored) {
        }
    }

    private void requireOpen() {
        if (closed.get()) {
            throw new IllegalStateException("PTY is closed");
        }
    }

    private static void closeAfterFailure(int descriptor) {
        if (descriptor < 0 || CLOSE == null) {
            return;
        }
        try {
            CLOSE.invoke(descriptor);
        } catch (Throwable ignored) {
        }
    }

    private int openSlave(Arena arena, Platform platform) throws Throwable {
        MemorySegment path = arena.allocateFrom(slave.toString());
        int descriptor = (int) OPEN.invoke(path, 2 | platform.noControllingTerminalFlag());
        if (descriptor < 0) {
            throw new IllegalStateException("cannot open PTY slave");
        }
        return descriptor;
    }

    private static MethodHandle lookup(String symbol, FunctionDescriptor descriptor) {
        try {
            Linker linker = Linker.nativeLinker();
            return linker.defaultLookup()
                    .find(symbol)
                    .map(address -> linker.downcallHandle(address, descriptor))
                    .orElse(null);
        } catch (RuntimeException unavailable) {
            return null;
        }
    }

    private static MethodHandle variadic(String symbol, FunctionDescriptor descriptor) {
        try {
            Linker linker = Linker.nativeLinker();
            SymbolLookup symbols = linker.defaultLookup();
            return symbols.find(symbol)
                    .map(address -> linker.downcallHandle(address, descriptor, Linker.Option.firstVariadicArg(2)))
                    .orElse(null);
        } catch (RuntimeException unavailable) {
            return null;
        }
    }

    private static Platform requirePlatform() {
        Platform value = platform();
        if (value == null) {
            throw new UnsupportedOperationException("POSIX PTY is unsupported on this platform");
        }
        return value;
    }

    private static Platform platform() {
        String value = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (value.contains("mac")) {
            return Platform.MACOS;
        }
        if (value.contains("linux")) {
            return Platform.LINUX;
        }
        return null;
    }

    private enum Platform {
        MACOS(0x00020000, 0x80087467L, 0x20007461L),
        LINUX(0x00000100, 0x00005414L, 0x0000540eL);

        private final int noControllingTerminalFlag;
        private final long setWindowSize;
        private final long setControllingTerminal;

        Platform(int noControllingTerminalFlag, long setWindowSize, long setControllingTerminal) {
            this.noControllingTerminalFlag = noControllingTerminalFlag;
            this.setWindowSize = setWindowSize;
            this.setControllingTerminal = setControllingTerminal;
        }

        int noControllingTerminalFlag() {
            return noControllingTerminalFlag;
        }

        long setWindowSize() {
            return setWindowSize;
        }

        long setControllingTerminal() {
            return setControllingTerminal;
        }
    }
}
