package com.javaclaw.nativehost.ffm;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_INT;

/** 拥有 ConPTY、目标进程、Job Object 与临时 AppContainer 文件授权。 */
final class WindowsPseudoConsole implements AutoCloseable {
    private static final int WAIT_OBJECT_0 = 0;
    private static final int WAIT_TIMEOUT = 258;
    private static final int ERROR_BROKEN_PIPE = 109;
    private static final int ERROR_NO_DATA = 232;
    private static final int ERROR_OPERATION_ABORTED = 995;
    private static final int CLOSED_EXIT = 137;

    private final MemorySegment pseudoConsole;
    private final MemorySegment input;
    private final MemorySegment output;
    private final MemorySegment process;
    private final WindowsProcessSecurity security;
    private final WindowsAppContainerScope scope;
    private final AtomicBoolean inputClosed = new AtomicBoolean();
    private final AtomicBoolean pseudoClosed = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();

    WindowsPseudoConsole(
            MemorySegment pseudoConsole,
            MemorySegment input,
            MemorySegment output,
            MemorySegment process,
            WindowsProcessSecurity security,
            WindowsAppContainerScope scope) {
        this.pseudoConsole = pseudoConsole;
        this.input = input;
        this.output = output;
        this.process = process;
        this.security = security;
        this.scope = scope;
    }

    byte[] read(int maximumBytes) throws IOException {
        if (maximumBytes < 1 || maximumBytes > 1024 * 1024) {
            throw new IllegalArgumentException("ConPTY read bound is invalid");
        }
        if (closed.get()) {
            return null;
        }
        var backend = WindowsSandboxNative.requireBackend();
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment buffer = arena.allocate(maximumBytes);
            MemorySegment count = arena.allocate(JAVA_INT);
            var result = backend.invoke(backend.readFile, output, buffer, maximumBytes, count, MemorySegment.NULL);
            if (result.number() == 0) {
                if (endOfStream(result.error())) {
                    return null;
                }
                throw WindowsSandboxNative.error("ReadFile(ConPTY)", result.error());
            }
            int bytes = count.get(JAVA_INT, 0);
            if (bytes < 0 || bytes > maximumBytes) {
                throw new IOException("ReadFile returned an invalid ConPTY byte count");
            }
            return bytes == 0 ? null : buffer.asSlice(0, bytes).toArray(JAVA_BYTE);
        }
    }

    synchronized void write(byte[] bytes) throws IOException {
        byte[] copy = Objects.requireNonNull(bytes, "bytes").clone();
        if (copy.length == 0) {
            return;
        }
        if (copy.length > 1024 * 1024) {
            throw new IllegalArgumentException("ConPTY write bound is invalid");
        }
        if (closed.get() || inputClosed.get()) {
            throw new IOException("ConPTY input is closed");
        }
        writeFully(copy);
    }

    synchronized void closeInput() {
        if (inputClosed.compareAndSet(false, true)) {
            WindowsSandboxNative.cancelIoQuietly(input);
            WindowsSandboxNative.closeHandleQuietly(input);
        }
    }

    synchronized void resize(int columns, int rows) throws IOException {
        if (closed.get() || pseudoClosed.get()) {
            throw new IOException("ConPTY is closed");
        }
        var backend = WindowsSandboxNative.requireBackend();
        try (Arena arena = Arena.ofConfined()) {
            var result = backend.invoke(
                    backend.resizePseudoConsole, pseudoConsole, WindowsSandboxNative.coord(arena, columns, rows));
            if (result.number() != 0) {
                throw WindowsSandboxNative.status("ResizePseudoConsole", result.number());
            }
        }
    }

    void interrupt() throws IOException {
        write(new byte[] {3});
    }

    void terminate(int exitCode) {
        if (!closed.get()) {
            security.terminate(exitCode);
        }
    }

    boolean isAlive() throws IOException {
        var result = WindowsSandboxNative.requireBackend()
                .invoke(WindowsSandboxNative.requireBackend().waitForSingleObject, process, 0);
        if (result.number() == WAIT_TIMEOUT) {
            return true;
        }
        if (result.number() == WAIT_OBJECT_0) {
            return false;
        }
        throw WindowsSandboxNative.error("WaitForSingleObject", result.error());
    }

    Integer awaitExit(Duration timeout) throws IOException {
        Duration checked = Objects.requireNonNull(timeout, "timeout");
        if (checked.isNegative() || checked.compareTo(Duration.ofHours(24)) > 0) {
            throw new IllegalArgumentException("ConPTY wait timeout is invalid");
        }
        var backend = WindowsSandboxNative.requireBackend();
        var waited = backend.invoke(backend.waitForSingleObject, process, Math.toIntExact(checked.toMillis()));
        if (waited.number() == WAIT_TIMEOUT) {
            return null;
        }
        if (waited.number() != WAIT_OBJECT_0) {
            throw WindowsSandboxNative.error("WaitForSingleObject", waited.error());
        }
        return exitCode(backend);
    }

    void finishOutput() {
        if (pseudoClosed.compareAndSet(false, true)) {
            WindowsSandboxNative.closePseudoConsole(pseudoConsole);
        }
    }

    @Override
    public void close() throws IOException {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        boolean rootAlive = isAliveDuringClose();
        security.terminate(CLOSED_EXIT);
        if (rootAlive) {
            awaitDuringClose();
        }
        closeInput();
        WindowsSandboxNative.cancelIoQuietly(output);
        WindowsSandboxNative.closeHandleQuietly(output);
        finishOutput();
        WindowsSandboxNative.closeHandleQuietly(process);
        security.close();
        scope.close();
    }

    private void writeFully(byte[] bytes) throws IOException {
        var backend = WindowsSandboxNative.requireBackend();
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment buffer = arena.allocateFrom(JAVA_BYTE, bytes);
            int offset = 0;
            while (offset < bytes.length) {
                MemorySegment count = arena.allocate(JAVA_INT);
                var result = backend.invoke(
                        backend.writeFile,
                        input,
                        buffer.asSlice(offset),
                        bytes.length - offset,
                        count,
                        MemorySegment.NULL);
                if (result.number() == 0) {
                    throw WindowsSandboxNative.error("WriteFile(ConPTY)", result.error());
                }
                int written = count.get(JAVA_INT, 0);
                if (written < 1 || written > bytes.length - offset) {
                    throw new IOException("WriteFile returned an invalid ConPTY byte count");
                }
                offset += written;
            }
        }
    }

    private int exitCode(WindowsSandboxNative.Backend backend) throws IOException {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment value = arena.allocate(JAVA_INT);
            var loaded = backend.invoke(backend.getExitCodeProcess, process, value);
            if (loaded.number() == 0) {
                throw WindowsSandboxNative.error("GetExitCodeProcess", loaded.error());
            }
            return value.get(JAVA_INT, 0);
        }
    }

    private boolean isAliveDuringClose() {
        try {
            return isAlive();
        } catch (IOException ignored) {
            return true;
        }
    }

    private void awaitDuringClose() {
        try {
            awaitExit(Duration.ofSeconds(5));
        } catch (IOException ignored) {
            // Job handle 的 kill-on-close 仍提供最终进程树回收。
        }
    }

    private boolean endOfStream(int error) {
        return error == ERROR_BROKEN_PIPE
                || error == ERROR_NO_DATA
                || error == ERROR_OPERATION_ABORTED && (closed.get() || pseudoClosed.get());
    }
}
