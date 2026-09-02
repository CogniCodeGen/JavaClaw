package com.javaclaw.nativehost.transport;

import java.io.IOException;
import java.nio.channels.AsynchronousCloseException;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;

import com.javaclaw.protocol.JsonRpcCodec;
import com.javaclaw.protocol.RpcConnection;

/**
 * Windows Named Pipe 阻塞监听器；每次 {@link #accept(JsonRpcCodec)} 创建独立 pipe instance。
 *
 * <p><strong>实现约束：</strong>{@link #close()} 不跨线程直接关闭仍在 {@code ConnectNamedPipe} 中使用的 handle，而是用同进程客户端连接唤醒 accept。锁保护
 * closed 与 pending，避免关闭发生在创建 handle 和发布 pending 之间。
 */
public final class WindowsNamedPipeRpcServer implements AutoCloseable {
    private static final Duration CLOSE_WAKE_TIMEOUT = Duration.ofSeconds(1);

    private final WindowsPipeName name;
    private final ReentrantLock stateLock = new ReentrantLock();

    private boolean closed;
    private boolean firstInstance = true;
    private WindowsNamedPipeConnection pending;

    private WindowsNamedPipeRpcServer(WindowsPipeName name) {
        this.name = name;
    }

    /**
     * 创建监听器。首个原生 instance 在第一次 accept 时创建并以 first-instance 标志防止名称劫持。
     *
     * @param name 已验证逻辑名称
     * @return 未开始接受连接的监听器
     */
    public static WindowsNamedPipeRpcServer bind(WindowsPipeName name) {
        Objects.requireNonNull(name, "name");
        if (!WindowsNamedPipeTransport.isSupported()) {
            throw new UnsupportedOperationException("Windows Named Pipe is unavailable on this platform");
        }
        return new WindowsNamedPipeRpcServer(name);
    }

    /**
     * 阻塞等待一条客户端连接。
     *
     * @param codec App Server 共享 JSON codec
     * @return 已连接且拥有原生句柄的 RPC 连接
     * @throws IOException 原生监听失败或监听器关闭
     */
    public RpcConnection accept(JsonRpcCodec codec) throws IOException {
        Objects.requireNonNull(codec, "codec");
        WindowsNamedPipeConnection connection = prepareInstance();
        boolean handedOff = false;
        try {
            WindowsKernel32.connectServer(connection.handle());
            stateLock.lock();
            try {
                if (closed) {
                    throw new AsynchronousCloseException();
                }
                pending = null;
                handedOff = true;
                return connection.rpc(codec);
            } finally {
                stateLock.unlock();
            }
        } finally {
            clearPending(connection);
            if (!handedOff) {
                connection.close();
            }
        }
    }

    /** 关闭监听器并唤醒阻塞 accept；重复调用不产生副作用。 */
    @Override
    public void close() throws IOException {
        boolean wake;
        stateLock.lock();
        try {
            if (closed) {
                return;
            }
            closed = true;
            wake = pending != null;
        } finally {
            stateLock.unlock();
        }
        if (wake) {
            try (RpcConnection ignored = new WindowsNamedPipeTransport(name, CLOSE_WAKE_TIMEOUT).connect()) {
                // 连接本身用于唤醒 ConnectNamedPipe；不发送任何协议帧。
            }
        }
    }

    private WindowsNamedPipeConnection prepareInstance() throws IOException {
        stateLock.lock();
        try {
            if (closed) {
                throw new AsynchronousCloseException();
            }
            if (pending != null) {
                throw new IllegalStateException("only one accept call may be pending");
            }
            var handle = WindowsKernel32.createServer(name, firstInstance);
            firstInstance = false;
            pending = new WindowsNamedPipeConnection(handle, true);
            return pending;
        } finally {
            stateLock.unlock();
        }
    }

    private void clearPending(WindowsNamedPipeConnection connection) {
        stateLock.lock();
        try {
            if (pending == connection) {
                pending = null;
            }
        } finally {
            stateLock.unlock();
        }
    }
}
