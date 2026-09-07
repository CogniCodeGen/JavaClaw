package com.javaclaw.nativehost.ffm;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;

/** 同一调用线程拥有 OVERLAPPED、缓冲和事件；取消经内核确认后才能释放 Arena。 */
final class WindowsGuardIo {
    private static final int IO_PENDING = 997;
    private static final int WAIT_OBJECT = 0;

    private WindowsGuardIo() {}

    static String exchange(MemorySegment pipe, String request) throws IOException {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        byte[] output = (request + "\n").getBytes(StandardCharsets.US_ASCII);
        if (transfer(pipe, output, false, deadline) != output.length) {
            throw new IOException("NETWORK_GUARD_PARTIAL_REQUEST");
        }
        ByteArrayOutputStream result = new ByteArrayOutputStream();
        while (result.size() < 256) {
            byte[] input = new byte[256 - result.size()];
            int count = transfer(pipe, input, true, deadline);
            if (count < 1) {
                throw new IOException("NETWORK_GUARD_CLOSED");
            }
            result.write(input, 0, count);
            String text = result.toString(StandardCharsets.US_ASCII);
            if (text.endsWith("\n")) {
                return text.substring(0, text.length() - 1);
            }
        }
        throw new IOException("NETWORK_GUARD_RESPONSE_TOO_LARGE");
    }

    private static int transfer(MemorySegment pipe, byte[] bytes, boolean read, long deadline) throws IOException {
        var backend = WindowsSandboxNative.requireBackend();
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment event = backend.invoke(Backend.CREATE_EVENT, MemorySegment.NULL, 1, 0, MemorySegment.NULL)
                    .address();
            WindowsSandboxNative.requireHandle(event, "CreateEventW(network guard)", 0);
            try {
                MemorySegment overlapped = arena.allocate(32, 8);
                overlapped.set(ADDRESS, 24, event);
                MemorySegment buffer = arena.allocate(bytes.length);
                if (!read) {
                    buffer.copyFrom(MemorySegment.ofArray(bytes));
                }
                int count = perform(pipe, buffer, overlapped, read, deadline, arena);
                if (count < 0 || count > bytes.length) {
                    throw new IOException("NETWORK_GUARD_INVALID_IO_LENGTH");
                }
                if (read) {
                    MemorySegment.ofArray(bytes).asSlice(0, count).copyFrom(buffer.asSlice(0, count));
                }
                return count;
            } finally {
                WindowsSandboxNative.closeHandleQuietly(event);
            }
        }
    }

    private static int perform(
            MemorySegment pipe,
            MemorySegment buffer,
            MemorySegment overlapped,
            boolean read,
            long deadline,
            Arena arena)
            throws IOException {
        var backend = WindowsSandboxNative.requireBackend();
        var started = backend.invoke(
                read ? backend.readFile : backend.writeFile,
                pipe,
                buffer,
                Math.toIntExact(buffer.byteSize()),
                MemorySegment.NULL,
                overlapped);
        if (started.number() == 0 && started.error() != IO_PENDING) {
            throw WindowsSandboxNative.error("network guard IO", started.error());
        }
        MemorySegment count = arena.allocate(JAVA_INT);
        try {
            if (started.number() == 0) {
                long millis = Math.max(
                        0, Duration.ofNanos(deadline - System.nanoTime()).toMillis());
                var waited = backend.invoke(
                        backend.waitForSingleObject,
                        overlapped.get(ADDRESS, 24),
                        Math.toIntExact(Math.min(millis, 5000)));
                if (waited.number() != WAIT_OBJECT || Thread.currentThread().isInterrupted()) {
                    throw new IOException("NETWORK_GUARD_IO_DEADLINE");
                }
            }
            var completed = backend.invoke(Backend.GET_RESULT, pipe, overlapped, count, 0);
            if (completed.number() == 0) {
                throw WindowsSandboxNative.error("GetOverlappedResult(network guard)", completed.error());
            }
            return count.get(JAVA_INT, 0);
        } catch (IOException | RuntimeException failure) {
            // 关闭句柄不能代替等待异步缓冲退休；先取消并确认完成，避免 handle 重用或 Arena use-after-free。
            backend.invoke(backend.cancelIoEx, pipe, overlapped);
            backend.invoke(Backend.GET_RESULT, pipe, overlapped, count, 1);
            throw failure;
        }
    }

    private static final class Backend {
        private static final SymbolLookup KERNEL = SymbolLookup.libraryLookup("kernel32.dll", Arena.global());
        private static final MethodHandle CREATE_EVENT =
                captured("CreateEventW", FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_INT, JAVA_INT, ADDRESS));
        private static final MethodHandle GET_RESULT =
                captured("GetOverlappedResult", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, JAVA_INT));

        private static MethodHandle captured(String symbol, FunctionDescriptor descriptor) {
            return Linker.nativeLinker()
                    .downcallHandle(
                            KERNEL.find(symbol).orElseThrow(),
                            descriptor,
                            Linker.Option.captureCallState("GetLastError"));
        }
    }
}
