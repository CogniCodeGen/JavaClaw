package com.javaclaw.nativehost.ffm;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.nio.channels.NonReadableChannelException;
import java.nio.channels.NonWritableChannelException;
import java.nio.channels.SeekableByteChannel;

import static java.lang.foreign.ValueLayout.JAVA_INT;

/** 单个固定文件句柄上的同步通道；每次传输最多 32 KiB，位置与关闭受同一锁保护。 */
final class WindowsWorkspaceChannel implements SeekableByteChannel {
    private static final int CHUNK_BYTES = 32 * 1024;
    private final WindowsFileHandle handle;
    private final boolean readable;
    private final boolean writable;
    private long position;

    WindowsWorkspaceChannel(WindowsFileHandle handle, boolean readable, boolean writable) {
        this.handle = handle;
        this.readable = readable;
        this.writable = writable;
    }

    @Override
    public synchronized int read(ByteBuffer destination) throws IOException {
        handle.address();
        if (!readable) {
            throw new NonReadableChannelException();
        }
        if (destination.isReadOnly()) {
            throw new IllegalArgumentException("read destination must be writable");
        }
        if (!destination.hasRemaining()) {
            return 0;
        }
        int length = Math.min(destination.remaining(), CHUNK_BYTES);
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment bytes = arena.allocate(length);
            MemorySegment count = arena.allocate(JAVA_INT);
            seek(position);
            var backend = WindowsSandboxNative.requireBackend();
            var result = backend.invoke(backend.readFile, handle.address(), bytes, length, count, MemorySegment.NULL);
            if (result.number() == 0) {
                if (result.error() == 38) {
                    return -1;
                }
                throw WindowsSandboxNative.error("ReadFile", result.error());
            }
            int read = checkedCount(count, length);
            destination.put(bytes.asSlice(0, read).asByteBuffer());
            position += read;
            return read == 0 ? -1 : read;
        }
    }

    @Override
    public synchronized int write(ByteBuffer source) throws IOException {
        requireWritable();
        if (!source.hasRemaining()) {
            return 0;
        }
        int length = Math.min(source.remaining(), CHUNK_BYTES);
        if (position > Long.MAX_VALUE - length) {
            throw new IOException("Windows file position exceeds supported range");
        }
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment bytes = arena.allocate(length);
            ByteBuffer portion = source.slice();
            portion.limit(length);
            bytes.asByteBuffer().put(portion);
            MemorySegment count = arena.allocate(JAVA_INT);
            seek(position);
            var backend = WindowsSandboxNative.requireBackend();
            var result = backend.invoke(backend.writeFile, handle.address(), bytes, length, count, MemorySegment.NULL);
            if (result.number() == 0) {
                throw WindowsSandboxNative.error("WriteFile", result.error());
            }
            int written = checkedCount(count, length);
            if (written == 0) {
                throw new IOException("Windows synchronous file write made no progress");
            }
            source.position(source.position() + written);
            position += written;
            return written;
        }
    }

    @Override
    public synchronized long position() throws IOException {
        handle.address();
        return position;
    }

    @Override
    public synchronized SeekableByteChannel position(long newPosition) throws IOException {
        handle.address();
        if (newPosition < 0) {
            throw new IllegalArgumentException("file position must be nonnegative");
        }
        position = newPosition;
        return this;
    }

    @Override
    public synchronized long size() throws IOException {
        return WindowsWorkspaceAttributes.read(handle).size();
    }

    @Override
    public synchronized SeekableByteChannel truncate(long size) throws IOException {
        requireWritable();
        if (size < 0) {
            throw new IllegalArgumentException("file size must be nonnegative");
        }
        if (size < size()) {
            seek(size);
            WindowsFileNative.requireSuccess(
                    WindowsFileNative.backend().setEndOfFile, "SetEndOfFile", handle.address());
            position = Math.min(position, size);
        }
        return this;
    }

    synchronized void force() throws IOException {
        requireWritable();
        WindowsFileNative.requireSuccess(WindowsFileNative.backend().flush, "FlushFileBuffers", handle.address());
    }

    private void requireWritable() throws IOException {
        handle.address();
        if (!writable) {
            throw new NonWritableChannelException();
        }
    }

    private void seek(long value) throws IOException {
        WindowsFileNative.requireSuccess(
                WindowsFileNative.backend().setFilePointer,
                "SetFilePointerEx",
                handle.address(),
                value,
                MemorySegment.NULL,
                0);
    }

    private static int checkedCount(MemorySegment count, int maximum) throws IOException {
        int value = count.get(JAVA_INT, 0);
        if (value < 0 || value > maximum) {
            throw new IOException("Windows file I/O returned an invalid byte count");
        }
        return value;
    }

    @Override
    public boolean isOpen() {
        return handle.isOpen();
    }

    @Override
    public synchronized void close() throws IOException {
        handle.close();
    }
}
