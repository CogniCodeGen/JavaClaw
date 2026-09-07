package com.javaclaw.nativehost.ffm;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.time.Duration;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;

/** 守护协议独享的本机 Pipe 连接；固定名称和权限，不依赖或复用 SDK/RPC 传输层。 */
final class WindowsGuardConnection {
    // READ_CONTROL 用于读取服务 Pipe 所有者；不请求与 FILE_CREATE_PIPE_INSTANCE 共用的位 4。
    static final int ACCESS = 0x00120003;
    // OVERLAPPED 与 SECURITY_IDENTIFICATION，身份核验前不允许对端冒充客户端。
    static final int FLAGS = 0x40110080;
    private static final String PIPE = "\\\\.\\pipe\\JavaClaw.NetworkGuard.v1";

    private WindowsGuardConnection() {}

    static MemorySegment connect(Duration timeout) throws IOException {
        if (timeout.isNegative() || timeout.isZero() || timeout.compareTo(Duration.ofMinutes(1)) > 0) {
            throw new IllegalArgumentException("Named Pipe connect timeout must be between 1 ms and 1 minute");
        }
        var backend = WindowsSandboxNative.requireBackend();
        long deadline = System.nanoTime() + timeout.toNanos();
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment path = WindowsSandboxNative.wide(arena, PIPE);
            while (true) {
                var open = backend.invoke(
                        Backend.CREATE_FILE, path, ACCESS, 0, MemorySegment.NULL, 3, FLAGS, MemorySegment.NULL);
                if (open.address().address() != -1L) {
                    return open.address();
                }
                if (!retryableConnect(open.error()) || System.nanoTime() >= deadline) {
                    throw error("CreateFileW", open.error());
                }
                long remainingMillis = Math.max(
                        1, Duration.ofNanos(deadline - System.nanoTime()).toMillis());
                int waitMillis = (int) Math.min(remainingMillis, 250L);
                var waited = backend.invoke(Backend.WAIT_PIPE, path, waitMillis);
                if (waited.number() == 0 && !retryableWait(waited.error())) {
                    throw error("WaitNamedPipeW", waited.error());
                }
                if (waited.number() == 0 && waited.error() == 2) {
                    pause(waitMillis);
                }
            }
        }
    }

    static void close(MemorySegment handle) throws IOException {
        var backend = WindowsSandboxNative.requireBackend();
        backend.invoke(backend.cancelIoEx, handle, MemorySegment.NULL);
        var result = backend.invoke(backend.closeHandle, handle);
        if (result.number() == 0) {
            throw error("CloseHandle", result.error());
        }
    }

    private static boolean retryableConnect(int error) {
        return error == 231 || error == 2;
    }

    private static boolean retryableWait(int error) {
        return retryableConnect(error) || error == 121;
    }

    private static void pause(int maximumMillis) throws InterruptedIOException {
        try {
            Thread.sleep(Duration.ofMillis(Math.min(maximumMillis, 25)));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            InterruptedIOException failure = new InterruptedIOException("Named Pipe connect was interrupted");
            failure.initCause(interrupted);
            throw failure;
        }
    }

    private static IOException error(String operation, int code) {
        return new IOException(operation + " failed with Win32 error " + Integer.toUnsignedString(code));
    }

    private static final class Backend {
        private static final SymbolLookup KERNEL = SymbolLookup.libraryLookup("Kernel32.dll", Arena.global());
        private static final MethodHandle CREATE_FILE = bind(
                "CreateFileW",
                FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_INT, JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT, ADDRESS));
        private static final MethodHandle WAIT_PIPE =
                bind("WaitNamedPipeW", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT));

        private static MethodHandle bind(String name, FunctionDescriptor descriptor) {
            return Linker.nativeLinker()
                    .downcallHandle(
                            KERNEL.findOrThrow(name), descriptor, Linker.Option.captureCallState("GetLastError"));
        }
    }
}
