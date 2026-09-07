package com.javaclaw.server.transport;

import java.io.IOException;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.javaclaw.nativehost.transport.WindowsNamedPipeRpcServer;
import com.javaclaw.nativehost.transport.WindowsPipeName;
import com.javaclaw.protocol.CanonicalJson;
import com.javaclaw.protocol.JsonRpcCodec;
import com.javaclaw.protocol.RpcConnection;

/** 在当前用户 Windows Named Pipe 上承载相互隔离的 Protocol v3 会话。 */
public final class WindowsNamedPipeSessionServer implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger(WindowsNamedPipeSessionServer.class);
    private static final int MAXIMUM_CONNECTIONS = 128;

    private final WindowsNamedPipeRpcServer listener;
    private final Semaphore permits = new Semaphore(MAXIMUM_CONNECTIONS);
    private final Set<RpcConnection> connections = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean closed = new AtomicBoolean();

    private WindowsNamedPipeSessionServer(WindowsNamedPipeRpcServer listener) {
        this.listener = listener;
    }

    /**
     * 创建 fail-closed Named Pipe 监听器。
     *
     * @param name 已验证逻辑名称
     * @return 会话服务端
     */
    public static WindowsNamedPipeSessionServer bind(WindowsPipeName name) {
        return new WindowsNamedPipeSessionServer(WindowsNamedPipeRpcServer.bind(name));
    }

    /**
     * 接受连接并在虚拟线程中运行独立会话，直到服务端关闭。
     *
     * @param json 规范 JSON codec
     * @param sessions 逐连接会话处理边界
     * @throws IOException 监听器意外失败
     */
    public void serve(CanonicalJson json, RpcSessionHandler sessions) throws IOException {
        Objects.requireNonNull(json, "json");
        Objects.requireNonNull(sessions, "sessions");
        JsonRpcCodec codec = new JsonRpcCodec(json);
        while (!closed.get()) {
            RpcConnection connection;
            try {
                connection = listener.accept(codec);
            } catch (IOException failure) {
                if (closed.get()) {
                    return;
                }
                throw failure;
            }
            if (!permits.tryAcquire()) {
                connection.close();
                continue;
            }
            connections.add(connection);
            Thread.ofVirtual().name("javaclaw-pipe-session").start(() -> serveClient(sessions, connection));
        }
    }

    private void serveClient(RpcSessionHandler sessions, RpcConnection connection) {
        try (RpcConnection owned = connection) {
            sessions.serve(owned);
        } catch (IOException failure) {
            if (!closed.get()) {
                LOGGER.debug("Named Pipe client connection ended with an I/O failure", failure);
            }
        } finally {
            connections.remove(connection);
            permits.release();
        }
    }

    /** 关闭监听器和全部活动连接；重复调用不产生副作用。 */
    @Override
    public void close() throws IOException {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        IOException failure = null;
        try {
            listener.close();
        } catch (IOException closeFailure) {
            failure = closeFailure;
        }
        for (RpcConnection connection : connections) {
            try {
                connection.close();
            } catch (IOException closeFailure) {
                if (failure == null) {
                    failure = closeFailure;
                } else {
                    failure.addSuppressed(closeFailure);
                }
            }
        }
        connections.clear();
        if (failure != null) {
            throw failure;
        }
    }
}
