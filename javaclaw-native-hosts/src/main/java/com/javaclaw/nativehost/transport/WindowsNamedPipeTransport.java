package com.javaclaw.nativehost.transport;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.time.Duration;
import java.util.Objects;

import com.javaclaw.protocol.JsonRpcCodec;
import com.javaclaw.protocol.LocalTransport;
import com.javaclaw.protocol.RpcConnection;
import com.javaclaw.protocol.TransportKind;

/** 当前登录会话专用的 Windows Named Pipe 客户端 transport。 */
public final class WindowsNamedPipeTransport implements LocalTransport {
    private static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(5);

    private final WindowsPipeName name;
    private final Duration connectTimeout;

    /**
     * 创建使用五秒连接上限的 transport。
     *
     * @param name 已验证逻辑名称
     */
    public WindowsNamedPipeTransport(WindowsPipeName name) {
        this(name, DEFAULT_CONNECT_TIMEOUT);
    }

    /**
     * 创建 transport。
     *
     * @param name 已验证逻辑名称
     * @param connectTimeout 1 毫秒至 1 分钟的连接上限
     */
    public WindowsNamedPipeTransport(WindowsPipeName name, Duration connectTimeout) {
        this.name = Objects.requireNonNull(name, "name");
        this.connectTimeout = Objects.requireNonNull(connectTimeout, "connectTimeout");
    }

    /** @return 当前平台是否提供所需 Win32 API */
    public static boolean isSupported() {
        return WindowsKernel32.isSupported();
    }

    /** @return {@link TransportKind#NAMED_PIPE} */
    @Override
    public TransportKind kind() {
        return TransportKind.NAMED_PIPE;
    }

    /**
     * 连接 Named Pipe。
     *
     * @return 使用 Protocol v3 framing 的 RPC 连接
     * @throws IOException 服务不可用、超时或原生调用失败
     */
    @Override
    public RpcConnection connect() throws IOException {
        MemorySegment handle = WindowsKernel32.connectClient(name, connectTimeout);
        WindowsNamedPipeConnection connection = new WindowsNamedPipeConnection(handle, false);
        try {
            return connection.rpc(new JsonRpcCodec());
        } catch (RuntimeException failure) {
            try {
                connection.close();
            } catch (IOException closeFailure) {
                failure.addSuppressed(closeFailure);
            }
            throw failure;
        }
    }
}
