package com.javaclaw.nativehost.ffm;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_CHAR;
import static java.lang.foreign.ValueLayout.JAVA_INT;

/** Minimal current-user-only Windows Named Pipe bindings. No JNI/JNA bridge is used. */
public final class WindowsNamedPipes {
    private static final int TOKEN_QUERY = 0x0008;
    private static final int TOKEN_USER = 1;
    private static final int GENERIC_READ = 0x80000000;
    private static final int GENERIC_WRITE = 0x40000000;
    private static final int OPEN_EXISTING = 3;
    private static final int PIPE_ACCESS_DUPLEX = 0x00000003;
    private static final int FILE_FLAG_FIRST_PIPE_INSTANCE = 0x00080000;
    private static final int PIPE_TYPE_BYTE = 0;
    private static final int PIPE_READMODE_BYTE = 0;
    private static final int PIPE_WAIT = 0;
    private static final int PIPE_REJECT_REMOTE_CLIENTS = 0x00000008;
    private static final int ERROR_PIPE_CONNECTED = 535;
    private static final int SDDL_REVISION_1 = 1;
    private static final long INVALID_HANDLE = -1L;
    private static final int MAX_IO_BYTES = 1024 * 1024;
    private static final MemoryLayout SECURITY_ATTRIBUTES = MemoryLayout.structLayout(
            JAVA_INT.withName("length"),
            MemoryLayout.paddingLayout(4),
            ADDRESS.withName("descriptor"),
            JAVA_INT.withName("inherit"),
            MemoryLayout.paddingLayout(4));

    private WindowsNamedPipes() {}

    /** 创建首个当前用户独占 Named Pipe 实例；bufferBytes 为 4096–1048576 字节，存在同名实例时失败，返回句柄由调用方关闭。 */
    public static ServerPipe createServer(String pipeName, int bufferBytes) throws IOException {
        return createServer(pipeName, bufferBytes, true);
    }

    /**
     * 以仅当前 SID 的 DACL 和 remote-client reject 创建管道；bufferBytes 为 4096–1048576 字节，firstInstance
     * 控制首实例占用检查。返回值必须关闭，连接后仍需复核客户端 SID。
     */
    public static ServerPipe createServer(String pipeName, int bufferBytes, boolean firstInstance) throws IOException {
        requireWindows();
        String path = pipePath(pipeName);
        if (bufferBytes < 4_096 || bufferBytes > MAX_IO_BYTES) {
            throw new IllegalArgumentException("pipe buffer must be between 4096 and 1048576");
        }
        String currentSid = currentProcessSid();
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment descriptorOut = arena.allocate(ADDRESS);
            MemorySegment sddl = wide(arena, "D:P(A;;GA;;;" + currentSid + ")");
            int converted = callInt(Bindings.CONVERT_SDDL, sddl, SDDL_REVISION_1, descriptorOut, MemorySegment.NULL);
            if (converted == 0) {
                throw failure("cannot build Named Pipe DACL");
            }
            MemorySegment descriptor = descriptorOut.get(ADDRESS, 0);
            try {
                MemorySegment attributes = arena.allocate(SECURITY_ATTRIBUTES);
                attributes.set(JAVA_INT, 0, Math.toIntExact(SECURITY_ATTRIBUTES.byteSize()));
                attributes.set(ADDRESS, 8, descriptor);
                attributes.set(JAVA_INT, 16, 0);
                MemorySegment handle = callAddress(
                        Bindings.CREATE_NAMED_PIPE,
                        wide(arena, path),
                        PIPE_ACCESS_DUPLEX | (firstInstance ? FILE_FLAG_FIRST_PIPE_INSTANCE : 0),
                        PIPE_TYPE_BYTE | PIPE_READMODE_BYTE | PIPE_WAIT | PIPE_REJECT_REMOTE_CLIENTS,
                        255,
                        bufferBytes,
                        bufferBytes,
                        0,
                        attributes);
                requireHandle(handle, "CreateNamedPipeW");
                return new ServerPipe(handle, currentSid, path);
            } finally {
                callAddress(Bindings.LOCAL_FREE, descriptor);
            }
        }
    }

    /** 连接 pipeName 对应的本机管道并返回拥有句柄的客户端；仅支持 Windows，调用方必须关闭返回值，连接失败抛出 IOException。 */
    public static Pipe connectClient(String pipeName) throws IOException {
        requireWindows();
        String path = pipePath(pipeName);
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment handle = callAddress(
                    Bindings.CREATE_FILE,
                    wide(arena, path),
                    GENERIC_READ | GENERIC_WRITE,
                    0,
                    MemorySegment.NULL,
                    OPEN_EXISTING,
                    0,
                    MemorySegment.NULL);
            requireHandle(handle, "CreateFileW");
            return new Pipe(handle, path);
        }
    }

    /** 拥有一个 Windows Named Pipe HANDLE；每次 I/O 的 Arena 仅在调用期间存活，close 幂等归还句柄。 */
    public static class Pipe implements AutoCloseable {
        private final MemorySegment handle;
        private final String path;
        private final AtomicBoolean closed = new AtomicBoolean();

        private Pipe(MemorySegment handle, String path) {
            this.handle = handle;
            this.path = path;
        }

        /** 阻塞读取至 target 并返回字节数，管道断开返回 -1；target 长度须为 1–1048576，其他原生失败抛出 IOException。 */
        public int read(byte[] target) throws IOException {
            Objects.requireNonNull(target, "target");
            ensureOpen();
            if (target.length == 0 || target.length > MAX_IO_BYTES) {
                throw new IllegalArgumentException("pipe read buffer is invalid");
            }
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment buffer = arena.allocate(target.length);
                MemorySegment count = arena.allocate(JAVA_INT);
                int ok = callInt(Bindings.READ_FILE, handle, buffer, target.length, count, MemorySegment.NULL);
                if (ok == 0) {
                    int error = lastError();
                    if (error == 109 || error == 232) {
                        return -1;
                    }
                    throw failure("ReadFile failed", error);
                }
                int bytes = count.get(JAVA_INT, 0);
                MemorySegment.copy(buffer, JAVA_BYTE, 0, target, 0, bytes);
                return bytes;
            }
        }

        /** 写完 source 的指定有效切片；length 为 1–1048576 字节，循环处理部分写入，失败或无进展时抛出 IOException。 */
        public void write(byte[] source, int offset, int length) throws IOException {
            Objects.requireNonNull(source, "source");
            ensureOpen();
            Objects.checkFromIndexSize(offset, length, source.length);
            if (length < 1 || length > MAX_IO_BYTES) {
                throw new IllegalArgumentException("pipe write length is invalid");
            }
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment buffer = arena.allocate(length);
                MemorySegment.copy(source, offset, buffer, JAVA_BYTE, 0, length);
                int written = 0;
                while (written < length) {
                    MemorySegment count = arena.allocate(JAVA_INT);
                    int ok = callInt(
                            Bindings.WRITE_FILE,
                            handle,
                            buffer.asSlice(written),
                            length - written,
                            count,
                            MemorySegment.NULL);
                    if (ok == 0) {
                        throw failure("WriteFile failed");
                    }
                    int step = count.get(JAVA_INT, 0);
                    if (step < 1) {
                        throw new IOException("WriteFile made no progress");
                    }
                    written += step;
                }
            }
        }

        /** 返回已规范化的本机管道名称；不暴露客户端 SID 或安全描述符。 */
        public String path() {
            return path;
        }

        /** 借用当前 HANDLE 供子类原生操作；不转移所有权，子类不得独立 CloseHandle 或在 Pipe 关闭后使用。 */
        protected final MemorySegment handle() {
            return handle;
        }

        private void ensureOpen() throws IOException {
            if (closed.get()) {
                throw new IOException("Named Pipe is closed");
            }
        }

        @Override
        public void close() {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            callIntUnchecked(Bindings.CLOSE_HANDLE, handle);
        }
    }

    /** 服务端管道实例；acceptCurrentUser 完成连接并复核 SID 后才可处理应用数据。 */
    public static final class ServerPipe extends Pipe {
        private final String expectedSid;

        private ServerPipe(MemorySegment handle, String expectedSid, String path) {
            super(handle, path);
            this.expectedSid = expectedSid;
        }

        /** Accepts one connection and verifies the impersonated client token against owner SID. */
        public void acceptCurrentUser() throws IOException {
            int connected = callInt(Bindings.CONNECT_NAMED_PIPE, handle(), MemorySegment.NULL);
            if (connected == 0 && lastError() != ERROR_PIPE_CONNECTED) {
                throw failure("ConnectNamedPipe failed");
            }
            boolean impersonated = callInt(Bindings.IMPERSONATE_CLIENT, handle()) != 0;
            if (!impersonated) {
                disconnect();
                throw failure("ImpersonateNamedPipeClient failed");
            }
            try {
                String actual = currentThreadSid();
                if (!expectedSid.equals(actual)) {
                    disconnect();
                    throw new IOException("Named Pipe client SID does not match current user");
                }
            } finally {
                callIntUnchecked(Bindings.REVERT_TO_SELF);
            }
        }

        /** 尝试刷新并断开当前客户端，保留实例 HANDLE 以供关闭或再次接受；刷新可能等待客户端读取，不应在共享协议线程调用。 */
        public void disconnect() {
            callIntUnchecked(Bindings.FLUSH_FILE_BUFFERS, handle());
            callIntUnchecked(Bindings.DISCONNECT_NAMED_PIPE, handle());
        }

        @Override
        public void close() {
            disconnect();
            super.close();
        }
    }

    private static String currentProcessSid() throws IOException {
        MemorySegment process = callAddress(Bindings.GET_CURRENT_PROCESS);
        return tokenSid(process, false);
    }

    private static String currentThreadSid() throws IOException {
        MemorySegment thread = callAddress(Bindings.GET_CURRENT_THREAD);
        return tokenSid(thread, true);
    }

    private static String tokenSid(MemorySegment owner, boolean thread) throws IOException {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment tokenOut = arena.allocate(ADDRESS);
            int opened = thread
                    ? callInt(Bindings.OPEN_THREAD_TOKEN, owner, TOKEN_QUERY, 1, tokenOut)
                    : callInt(Bindings.OPEN_PROCESS_TOKEN, owner, TOKEN_QUERY, tokenOut);
            if (opened == 0) {
                throw failure("cannot open Windows access token");
            }
            MemorySegment token = tokenOut.get(ADDRESS, 0);
            try {
                MemorySegment required = arena.allocate(JAVA_INT);
                callInt(Bindings.GET_TOKEN_INFORMATION, token, TOKEN_USER, MemorySegment.NULL, 0, required);
                int size = required.get(JAVA_INT, 0);
                if (size < ADDRESS.byteSize() || size > 64 * 1024) {
                    throw new IOException("Windows token user information has invalid size");
                }
                MemorySegment info = arena.allocate(size);
                if (callInt(Bindings.GET_TOKEN_INFORMATION, token, TOKEN_USER, info, size, required) == 0) {
                    throw failure("GetTokenInformation failed");
                }
                return sidString(info.get(ADDRESS, 0), arena);
            } finally {
                callIntUnchecked(Bindings.CLOSE_HANDLE, token);
            }
        }
    }

    private static String sidString(MemorySegment sid, Arena arena) throws IOException {
        MemorySegment textOut = arena.allocate(ADDRESS);
        if (callInt(Bindings.CONVERT_SID_TO_STRING, sid, textOut) == 0) {
            throw failure("ConvertSidToStringSidW failed");
        }
        MemorySegment text = textOut.get(ADDRESS, 0);
        try {
            MemorySegment readable = text.reinterpret(64 * 1024);
            StringBuilder result = new StringBuilder();
            for (long offset = 0; offset < 64 * 1024; offset += 2) {
                char value = readable.get(JAVA_CHAR, offset);
                if (value == 0) {
                    return result.toString();
                }
                result.append(value);
            }
            throw new IOException("Windows SID string is unterminated");
        } finally {
            callAddress(Bindings.LOCAL_FREE, text);
        }
    }

    private static MemorySegment wide(Arena arena, String value) {
        MemorySegment result = arena.allocate((long) (value.length() + 1) * 2, 2);
        for (int index = 0; index < value.length(); index++) {
            result.set(JAVA_CHAR, (long) index * 2, value.charAt(index));
        }
        result.set(JAVA_CHAR, (long) value.length() * 2, (char) 0);
        return result;
    }

    private static String pipePath(String value) {
        String name = Objects.requireNonNull(value, "pipeName").strip();
        if (!name.matches("javaclaw-v4-[A-Za-z0-9._-]{1,120}")) {
            throw new IllegalArgumentException("invalid JavaClaw Named Pipe name");
        }
        return "\\\\.\\pipe\\" + name;
    }

    private static void requireWindows() {
        if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("windows")) {
            throw new UnsupportedOperationException("Windows Named Pipes require Windows");
        }
    }

    private static void requireHandle(MemorySegment handle, String operation) throws IOException {
        if (handle == null || handle.equals(MemorySegment.NULL) || handle.address() == INVALID_HANDLE) {
            throw failure(operation + " failed");
        }
    }

    private static IOException failure(String message) {
        return failure(message, lastError());
    }

    private static IOException failure(String message, int error) {
        return new IOException(message + " (Windows error " + error + ")");
    }

    private static int lastError() {
        try {
            return (int) Bindings.GET_LAST_ERROR.invokeExact();
        } catch (Throwable failure) {
            return -1;
        }
    }

    private static int callInt(MethodHandle handle, Object... arguments) throws IOException {
        try {
            return (int) handle.invokeWithArguments(arguments);
        } catch (Throwable failure) {
            throw new IOException("Windows FFM call failed", failure);
        }
    }

    private static int callIntUnchecked(MethodHandle handle, Object... arguments) {
        try {
            return (int) handle.invokeWithArguments(arguments);
        } catch (Throwable ignored) {
            return 0;
        }
    }

    private static MemorySegment callAddress(MethodHandle handle, Object... arguments) throws IOException {
        try {
            return (MemorySegment) handle.invokeWithArguments(arguments);
        } catch (Throwable failure) {
            throw new IOException("Windows FFM call failed", failure);
        }
    }

    private static final class Bindings {
        private static final Linker LINKER = Linker.nativeLinker();
        private static final SymbolLookup KERNEL = SymbolLookup.libraryLookup("kernel32", Arena.global());
        private static final SymbolLookup ADVAPI = SymbolLookup.libraryLookup("advapi32", Arena.global());

        private static final MethodHandle GET_LAST_ERROR =
                function(KERNEL, "GetLastError", FunctionDescriptor.of(JAVA_INT));
        private static final MethodHandle GET_CURRENT_PROCESS =
                function(KERNEL, "GetCurrentProcess", FunctionDescriptor.of(ADDRESS));
        private static final MethodHandle GET_CURRENT_THREAD =
                function(KERNEL, "GetCurrentThread", FunctionDescriptor.of(ADDRESS));
        private static final MethodHandle OPEN_PROCESS_TOKEN =
                function(ADVAPI, "OpenProcessToken", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS));
        private static final MethodHandle OPEN_THREAD_TOKEN = function(
                ADVAPI, "OpenThreadToken", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT, ADDRESS));
        private static final MethodHandle GET_TOKEN_INFORMATION = function(
                ADVAPI,
                "GetTokenInformation",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, JAVA_INT, ADDRESS));
        private static final MethodHandle CONVERT_SID_TO_STRING =
                function(ADVAPI, "ConvertSidToStringSidW", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
        private static final MethodHandle CONVERT_SDDL = function(
                ADVAPI,
                "ConvertStringSecurityDescriptorToSecurityDescriptorW",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, ADDRESS));
        private static final MethodHandle CREATE_NAMED_PIPE = function(
                KERNEL,
                "CreateNamedPipeW",
                FunctionDescriptor.of(
                        ADDRESS, ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, ADDRESS));
        private static final MethodHandle CONNECT_NAMED_PIPE =
                function(KERNEL, "ConnectNamedPipe", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
        private static final MethodHandle CREATE_FILE = function(
                KERNEL,
                "CreateFileW",
                FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_INT, JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT, ADDRESS));
        private static final MethodHandle READ_FILE = function(
                KERNEL, "ReadFile", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, ADDRESS, ADDRESS));
        private static final MethodHandle WRITE_FILE = function(
                KERNEL, "WriteFile", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, ADDRESS, ADDRESS));
        private static final MethodHandle FLUSH_FILE_BUFFERS =
                function(KERNEL, "FlushFileBuffers", FunctionDescriptor.of(JAVA_INT, ADDRESS));
        private static final MethodHandle DISCONNECT_NAMED_PIPE =
                function(KERNEL, "DisconnectNamedPipe", FunctionDescriptor.of(JAVA_INT, ADDRESS));
        private static final MethodHandle CLOSE_HANDLE =
                function(KERNEL, "CloseHandle", FunctionDescriptor.of(JAVA_INT, ADDRESS));
        private static final MethodHandle LOCAL_FREE =
                function(KERNEL, "LocalFree", FunctionDescriptor.of(ADDRESS, ADDRESS));
        private static final MethodHandle IMPERSONATE_CLIENT =
                function(ADVAPI, "ImpersonateNamedPipeClient", FunctionDescriptor.of(JAVA_INT, ADDRESS));
        private static final MethodHandle REVERT_TO_SELF =
                function(ADVAPI, "RevertToSelf", FunctionDescriptor.of(JAVA_INT));

        private static MethodHandle function(SymbolLookup lookup, String name, FunctionDescriptor descriptor) {
            MemorySegment symbol = lookup.find(name)
                    .orElseThrow(() -> new UnsupportedOperationException("Windows API is unavailable: " + name));
            return LINKER.downcallHandle(symbol, descriptor);
        }
    }
}
