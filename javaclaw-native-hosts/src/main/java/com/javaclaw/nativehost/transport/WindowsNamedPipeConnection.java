package com.javaclaw.nativehost.transport;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.foreign.MemorySegment;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

import com.javaclaw.protocol.JsonRpcCodec;
import com.javaclaw.protocol.RpcConnection;
import com.javaclaw.protocol.StreamRpcConnection;

/**
 * 拥有单个 Windows pipe handle 的双向流连接。
 *
 * <p><strong>实现约束：</strong>输入流和输出流共享同一 handle；任一流关闭都会原子关闭整个连接。关闭前调用 {@code CancelIoEx}，用于唤醒在其他线程阻塞的同步读写。服务端句柄还会执行
 * {@code DisconnectNamedPipe}。
 */
final class WindowsNamedPipeConnection implements AutoCloseable {
    private final MemorySegment handle;
    private final boolean serverEnd;
    private final AtomicBoolean closed = new AtomicBoolean();

    WindowsNamedPipeConnection(MemorySegment handle, boolean serverEnd) {
        this.handle = Objects.requireNonNull(handle, "handle");
        if (handle.address() == 0 || handle.address() == -1L) {
            throw new IllegalArgumentException("handle is invalid");
        }
        this.serverEnd = serverEnd;
    }

    RpcConnection rpc(JsonRpcCodec codec) {
        Objects.requireNonNull(codec, "codec");
        return new StreamRpcConnection(new PipeInputStream(), new PipeOutputStream(), codec);
    }

    MemorySegment handle() {
        return handle;
    }

    @Override
    public void close() throws IOException {
        if (closed.compareAndSet(false, true)) {
            WindowsKernel32.close(handle, serverEnd);
        }
    }

    private void requireOpen() throws IOException {
        if (closed.get()) {
            throw new IOException("Named Pipe connection is closed");
        }
    }

    private final class PipeInputStream extends InputStream {
        @Override
        public int read() throws IOException {
            byte[] single = new byte[1];
            int count = read(single, 0, 1);
            return count < 0 ? -1 : Byte.toUnsignedInt(single[0]);
        }

        @Override
        public int read(byte[] target, int offset, int length) throws IOException {
            Objects.checkFromIndexSize(offset, length, target.length);
            if (length == 0) {
                return 0;
            }
            requireOpen();
            return WindowsKernel32.read(handle, target, offset, length);
        }

        @Override
        public void close() throws IOException {
            WindowsNamedPipeConnection.this.close();
        }
    }

    private final class PipeOutputStream extends OutputStream {
        @Override
        public void write(int value) throws IOException {
            write(new byte[] {(byte) value}, 0, 1);
        }

        @Override
        public void write(byte[] source, int offset, int length) throws IOException {
            Objects.checkFromIndexSize(offset, length, source.length);
            if (length == 0) {
                return;
            }
            requireOpen();
            WindowsKernel32.write(handle, source, offset, length);
        }

        @Override
        public void close() throws IOException {
            WindowsNamedPipeConnection.this.close();
        }
    }
}
