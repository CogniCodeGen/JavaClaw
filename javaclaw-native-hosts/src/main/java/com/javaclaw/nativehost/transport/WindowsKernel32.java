package com.javaclaw.nativehost.transport;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.StructLayout;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.VarHandle;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;

import static java.lang.foreign.MemoryLayout.PathElement.groupElement;
import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_INT;

/**
 * Windows Named Pipe 所需的最小 Win32 FFM 映射。
 *
 * <p><strong>实现约束：</strong>原生库只在真实 Windows 进程中惰性加载。每次可能失败的调用都用 FFM call-state 原子捕获 {@code GetLastError}，避免后续 JVM/FFM
 * 调用覆盖错误码。Named Pipe 的安全描述符仅授予 SYSTEM 与当前登录 SID 完全访问权，且显式拒绝远程客户端。
 */
final class WindowsKernel32 {
    private static final int PIPE_ACCESS_DUPLEX = 0x00000003;
    private static final int FILE_FLAG_FIRST_PIPE_INSTANCE = 0x00080000;
    private static final int PIPE_REJECT_REMOTE_CLIENTS = 0x00000008;
    private static final int PIPE_UNLIMITED_INSTANCES = 255;
    private static final int GENERIC_READ_WRITE = 0xC0000000;
    private static final int OPEN_EXISTING = 3;
    private static final int FILE_ATTRIBUTE_NORMAL = 0x00000080;
    private static final int TOKEN_QUERY = 0x00000008;
    private static final int TOKEN_GROUPS = 2;
    private static final int SE_GROUP_LOGON_ID = 0xC0000000;
    private static final int SDDL_REVISION_1 = 1;
    private static final int ERROR_INSUFFICIENT_BUFFER = 122;
    private static final int ERROR_SEM_TIMEOUT = 121;
    private static final int ERROR_FILE_NOT_FOUND = 2;
    private static final int ERROR_PIPE_BUSY = 231;
    private static final int ERROR_BROKEN_PIPE = 109;
    private static final int ERROR_NO_DATA = 232;
    private static final int ERROR_PIPE_NOT_CONNECTED = 233;
    private static final int ERROR_PIPE_CONNECTED = 535;
    private static final int BUFFER_BYTES = 64 * 1024;
    private static final long INVALID_HANDLE = -1L;
    private static final long TOKEN_GROUPS_OFFSET = 8;
    private static final long SID_AND_ATTRIBUTES_BYTES = 16;
    private static final StructLayout SECURITY_ATTRIBUTES = MemoryLayout.structLayout(
            JAVA_INT.withName("length"),
            MemoryLayout.paddingLayout(4),
            ADDRESS.withName("securityDescriptor"),
            JAVA_INT.withName("inheritHandle"),
            MemoryLayout.paddingLayout(4));

    private WindowsKernel32() {}

    static boolean isSupported() {
        if (!isWindows()) {
            return false;
        }
        try {
            Backend.instance();
            return true;
        } catch (RuntimeException | ExceptionInInitializerError unavailable) {
            return false;
        }
    }

    static MemorySegment createServer(WindowsPipeName name, boolean firstInstance) throws IOException {
        Backend backend = requireBackend();
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment path = wideString(arena, name.nativePath());
            try (SecurityAttributes security = backend.securityAttributes(arena)) {
                int openMode = PIPE_ACCESS_DUPLEX | (firstInstance ? FILE_FLAG_FIRST_PIPE_INSTANCE : 0);
                CallResult result = backend.invoke(
                        backend.createNamedPipe,
                        path,
                        openMode,
                        PIPE_REJECT_REMOTE_CLIENTS,
                        PIPE_UNLIMITED_INSTANCES,
                        BUFFER_BYTES,
                        BUFFER_BYTES,
                        0,
                        security.segment());
                MemorySegment handle = result.address();
                if (handle.address() == INVALID_HANDLE) {
                    throw new WindowsNativeException("CreateNamedPipeW", result.error());
                }
                return handle;
            }
        }
    }

    static void connectServer(MemorySegment handle) throws IOException {
        Backend backend = requireBackend();
        CallResult result = backend.invoke(backend.connectNamedPipe, handle, MemorySegment.NULL);
        if (result.number() == 0 && result.error() != ERROR_PIPE_CONNECTED) {
            throw new WindowsNativeException("ConnectNamedPipe", result.error());
        }
    }

    static MemorySegment connectClient(WindowsPipeName name, Duration timeout) throws IOException {
        if (timeout.isNegative() || timeout.isZero() || timeout.compareTo(Duration.ofMinutes(1)) > 0) {
            throw new IllegalArgumentException("Named Pipe connect timeout must be between 1 ms and 1 minute");
        }
        Backend backend = requireBackend();
        long deadline = System.nanoTime() + timeout.toNanos();
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment path = wideString(arena, name.nativePath());
            while (true) {
                CallResult open = backend.invoke(
                        backend.createFile,
                        path,
                        GENERIC_READ_WRITE,
                        0,
                        MemorySegment.NULL,
                        OPEN_EXISTING,
                        FILE_ATTRIBUTE_NORMAL,
                        MemorySegment.NULL);
                if (open.address().address() != INVALID_HANDLE) {
                    return open.address();
                }
                if (!isRetryableConnectError(open.error()) || System.nanoTime() >= deadline) {
                    throw new WindowsNativeException("CreateFileW", open.error());
                }
                long remainingMillis = Math.max(
                        1, Duration.ofNanos(deadline - System.nanoTime()).toMillis());
                int waitMillis = (int) Math.min(remainingMillis, 250L);
                CallResult waited = backend.invoke(backend.waitNamedPipe, path, waitMillis);
                if (waited.number() == 0 && !isRetryableWaitError(waited.error())) {
                    throw new WindowsNativeException("WaitNamedPipeW", waited.error());
                }
                if (waited.number() == 0 && waited.error() == ERROR_FILE_NOT_FOUND) {
                    pauseBeforeRetry(waitMillis);
                }
            }
        }
    }

    static int read(MemorySegment handle, byte[] target, int offset, int length) throws IOException {
        Backend backend = requireBackend();
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment buffer = arena.allocate(length);
            MemorySegment read = arena.allocate(JAVA_INT);
            CallResult result = backend.invoke(backend.readFile, handle, buffer, length, read, MemorySegment.NULL);
            if (result.number() == 0) {
                if (isEndOfPipe(result.error())) {
                    return -1;
                }
                throw new WindowsNativeException("ReadFile", result.error());
            }
            int count = read.get(JAVA_INT, 0);
            if (count == 0) {
                return -1;
            }
            MemorySegment.copy(buffer, JAVA_BYTE, 0, target, offset, count);
            return count;
        }
    }

    static void write(MemorySegment handle, byte[] source, int offset, int length) throws IOException {
        Backend backend = requireBackend();
        int writtenTotal = 0;
        while (writtenTotal < length) {
            int chunk = Math.min(BUFFER_BYTES, length - writtenTotal);
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment buffer = arena.allocate(chunk);
                MemorySegment.copy(source, offset + writtenTotal, buffer, JAVA_BYTE, 0, chunk);
                MemorySegment written = arena.allocate(JAVA_INT);
                CallResult result =
                        backend.invoke(backend.writeFile, handle, buffer, chunk, written, MemorySegment.NULL);
                if (result.number() == 0) {
                    throw new WindowsNativeException("WriteFile", result.error());
                }
                int count = written.get(JAVA_INT, 0);
                if (count < 1 || count > chunk) {
                    throw new IOException("WriteFile made invalid progress: " + count);
                }
                writtenTotal += count;
            }
        }
    }

    static void close(MemorySegment handle, boolean serverEnd) throws IOException {
        Backend backend = requireBackend();
        backend.invoke(backend.cancelIoEx, handle, MemorySegment.NULL);
        if (serverEnd) {
            backend.invoke(backend.disconnectNamedPipe, handle);
        }
        CallResult result = backend.invoke(backend.closeHandle, handle);
        if (result.number() == 0) {
            throw new WindowsNativeException("CloseHandle", result.error());
        }
    }

    private static boolean isEndOfPipe(int error) {
        return error == ERROR_BROKEN_PIPE || error == ERROR_NO_DATA || error == ERROR_PIPE_NOT_CONNECTED;
    }

    private static boolean isRetryableConnectError(int error) {
        return error == ERROR_PIPE_BUSY || error == ERROR_FILE_NOT_FOUND;
    }

    private static boolean isRetryableWaitError(int error) {
        return error == ERROR_PIPE_BUSY || error == ERROR_FILE_NOT_FOUND || error == ERROR_SEM_TIMEOUT;
    }

    private static void pauseBeforeRetry(int maximumMillis) throws InterruptedIOException {
        try {
            Thread.sleep(Duration.ofMillis(Math.min(maximumMillis, 25)));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            InterruptedIOException failure = new InterruptedIOException("Named Pipe connect was interrupted");
            failure.initCause(interrupted);
            throw failure;
        }
    }

    private static Backend requireBackend() {
        if (!isWindows()) {
            throw new UnsupportedOperationException("Windows Named Pipe is unavailable on this platform");
        }
        try {
            return Backend.instance();
        } catch (RuntimeException | ExceptionInInitializerError failure) {
            throw new UnsupportedOperationException("required Windows Named Pipe APIs are unavailable", failure);
        }
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("windows");
    }

    private static MemorySegment wideString(Arena arena, String value) {
        return arena.allocateFrom(value + '\0', StandardCharsets.UTF_16LE);
    }

    private record CallResult(Object value, int error) {
        int number() {
            return (int) value;
        }

        MemorySegment address() {
            return (MemorySegment) value;
        }
    }

    private record SecurityAttributes(MemorySegment segment, Backend backend, MemorySegment descriptor)
            implements AutoCloseable {
        @Override
        public void close() {
            backend.free(descriptor);
        }
    }

    private static final class Backend {
        private static final Linker LINKER = Linker.nativeLinker();
        private static final StructLayout CALL_STATE = Linker.Option.captureStateLayout();
        private static final VarHandle LAST_ERROR = CALL_STATE.varHandle(groupElement("GetLastError"));
        private static final Backend INSTANCE = new Backend();

        private final SymbolLookup kernel32 = SymbolLookup.libraryLookup("Kernel32.dll", Arena.global());
        private final SymbolLookup advapi32 = SymbolLookup.libraryLookup("Advapi32.dll", Arena.global());
        private final MethodHandle createNamedPipe = downcall(
                kernel32,
                "CreateNamedPipeW",
                FunctionDescriptor.of(
                        ADDRESS, ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, ADDRESS));
        private final MethodHandle connectNamedPipe =
                downcall(kernel32, "ConnectNamedPipe", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
        private final MethodHandle disconnectNamedPipe =
                downcall(kernel32, "DisconnectNamedPipe", FunctionDescriptor.of(JAVA_INT, ADDRESS));
        private final MethodHandle createFile = downcall(
                kernel32,
                "CreateFileW",
                FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_INT, JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT, ADDRESS));
        private final MethodHandle waitNamedPipe =
                downcall(kernel32, "WaitNamedPipeW", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT));
        private final MethodHandle readFile = downcall(
                kernel32, "ReadFile", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, ADDRESS, ADDRESS));
        private final MethodHandle writeFile = downcall(
                kernel32, "WriteFile", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, ADDRESS, ADDRESS));
        private final MethodHandle cancelIoEx =
                downcall(kernel32, "CancelIoEx", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
        private final MethodHandle closeHandle =
                downcall(kernel32, "CloseHandle", FunctionDescriptor.of(JAVA_INT, ADDRESS));
        private final MethodHandle getCurrentProcess =
                plainDowncall(kernel32, "GetCurrentProcess", FunctionDescriptor.of(ADDRESS));
        private final MethodHandle openProcessToken =
                downcall(advapi32, "OpenProcessToken", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS));
        private final MethodHandle getTokenInformation = downcall(
                advapi32,
                "GetTokenInformation",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, JAVA_INT, ADDRESS));
        private final MethodHandle convertSidToStringSid =
                downcall(advapi32, "ConvertSidToStringSidW", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
        private final MethodHandle convertSecurityDescriptor = downcall(
                advapi32,
                "ConvertStringSecurityDescriptorToSecurityDescriptorW",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, ADDRESS));
        private final MethodHandle localFree =
                plainDowncall(kernel32, "LocalFree", FunctionDescriptor.of(ADDRESS, ADDRESS));

        static Backend instance() {
            return INSTANCE;
        }

        CallResult invoke(MethodHandle handle, Object... arguments) throws IOException {
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment state = arena.allocate(CALL_STATE);
                Object[] captured = new Object[arguments.length + 1];
                captured[0] = state;
                System.arraycopy(arguments, 0, captured, 1, arguments.length);
                Object value = handle.invokeWithArguments(captured);
                return new CallResult(value, (int) LAST_ERROR.get(state, 0L));
            } catch (RuntimeException failure) {
                throw failure;
            } catch (Throwable failure) {
                throw new IOException("cannot invoke Win32 API", failure);
            }
        }

        SecurityAttributes securityAttributes(Arena arena) throws IOException {
            String logonSid = currentLogonSid();
            MemorySegment sddl = wideString(arena, "D:P(A;;GA;;;SY)(A;;GA;;;" + logonSid + ")");
            MemorySegment pointer = arena.allocate(ADDRESS);
            CallResult converted =
                    invoke(convertSecurityDescriptor, sddl, SDDL_REVISION_1, pointer, MemorySegment.NULL);
            if (converted.number() == 0) {
                throw new WindowsNativeException(
                        "ConvertStringSecurityDescriptorToSecurityDescriptorW", converted.error());
            }
            MemorySegment descriptor = pointer.get(ADDRESS, 0);
            MemorySegment attributes = arena.allocate(SECURITY_ATTRIBUTES);
            attributes.set(
                    JAVA_INT,
                    SECURITY_ATTRIBUTES.byteOffset(groupElement("length")),
                    Math.toIntExact(SECURITY_ATTRIBUTES.byteSize()));
            attributes.set(ADDRESS, SECURITY_ATTRIBUTES.byteOffset(groupElement("securityDescriptor")), descriptor);
            attributes.set(JAVA_INT, SECURITY_ATTRIBUTES.byteOffset(groupElement("inheritHandle")), 0);
            return new SecurityAttributes(attributes, this, descriptor);
        }

        private String currentLogonSid() throws IOException {
            MemorySegment token;
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment tokenPointer = arena.allocate(ADDRESS);
                MemorySegment process = invokePlain(getCurrentProcess);
                CallResult opened = invoke(openProcessToken, process, TOKEN_QUERY, tokenPointer);
                if (opened.number() == 0) {
                    throw new WindowsNativeException("OpenProcessToken", opened.error());
                }
                token = tokenPointer.get(ADDRESS, 0);
            }
            try {
                return readLogonSid(token);
            } finally {
                close(token, false);
            }
        }

        private String readLogonSid(MemorySegment token) throws IOException {
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment required = arena.allocate(JAVA_INT);
                CallResult sizing = invoke(getTokenInformation, token, TOKEN_GROUPS, MemorySegment.NULL, 0, required);
                int bytes = required.get(JAVA_INT, 0);
                if (sizing.number() != 0
                        || sizing.error() != ERROR_INSUFFICIENT_BUFFER
                        || bytes < TOKEN_GROUPS_OFFSET) {
                    throw new WindowsNativeException("GetTokenInformation(size)", sizing.error());
                }
                MemorySegment groups = arena.allocate(bytes);
                CallResult loaded = invoke(getTokenInformation, token, TOKEN_GROUPS, groups, bytes, required);
                if (loaded.number() == 0) {
                    throw new WindowsNativeException("GetTokenInformation", loaded.error());
                }
                int count = groups.get(JAVA_INT, 0);
                long maximum = (bytes - TOKEN_GROUPS_OFFSET) / SID_AND_ATTRIBUTES_BYTES;
                if (count < 0 || count > maximum) {
                    throw new IOException("TokenGroups contains an invalid group count");
                }
                for (int index = 0; index < count; index++) {
                    long entry = TOKEN_GROUPS_OFFSET + index * SID_AND_ATTRIBUTES_BYTES;
                    int attributes = groups.get(JAVA_INT, entry + ADDRESS.byteSize());
                    if ((attributes & SE_GROUP_LOGON_ID) == SE_GROUP_LOGON_ID) {
                        return sidString(groups.get(ADDRESS, entry), arena);
                    }
                }
                throw new IOException("current access token has no logon SID");
            }
        }

        private String sidString(MemorySegment sid, Arena arena) throws IOException {
            MemorySegment pointer = arena.allocate(ADDRESS);
            CallResult converted = invoke(convertSidToStringSid, sid, pointer);
            if (converted.number() == 0) {
                throw new WindowsNativeException("ConvertSidToStringSidW", converted.error());
            }
            MemorySegment value = pointer.get(ADDRESS, 0);
            try {
                return value.reinterpret(512).getString(0, StandardCharsets.UTF_16LE);
            } finally {
                free(value);
            }
        }

        private void free(MemorySegment value) {
            if (value.address() == 0) {
                return;
            }
            try {
                localFree.invoke(value);
            } catch (Throwable ignored) {
            }
        }

        private MemorySegment invokePlain(MethodHandle handle) throws IOException {
            try {
                return (MemorySegment) handle.invoke();
            } catch (RuntimeException failure) {
                throw failure;
            } catch (Throwable failure) {
                throw new IOException("cannot invoke Win32 API", failure);
            }
        }

        private static MethodHandle downcall(SymbolLookup lookup, String symbol, FunctionDescriptor descriptor) {
            return LINKER.downcallHandle(
                    lookup.findOrThrow(symbol), descriptor, Linker.Option.captureCallState("GetLastError"));
        }

        private static MethodHandle plainDowncall(SymbolLookup lookup, String symbol, FunctionDescriptor descriptor) {
            return LINKER.downcallHandle(lookup.findOrThrow(symbol), descriptor);
        }
    }
}
