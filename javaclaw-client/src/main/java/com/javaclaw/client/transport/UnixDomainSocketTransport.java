package com.javaclaw.client.transport;

import java.io.IOException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.Channels;
import java.nio.channels.SocketChannel;
import java.nio.file.Path;
import java.util.Objects;

import com.javaclaw.protocol.JsonRpcCodec;
import com.javaclaw.protocol.LocalTransport;
import com.javaclaw.protocol.RpcConnection;
import com.javaclaw.protocol.StreamRpcConnection;
import com.javaclaw.protocol.TransportKind;

/** macOS 与 Linux 的 Unix Domain Socket transport。 */
public final class UnixDomainSocketTransport implements LocalTransport {
    private final Path socketPath;

    /**
     * 创建 UDS transport。
     *
     * @param socketPath 本机 socket 文件
     */
    public UnixDomainSocketTransport(Path socketPath) {
        this.socketPath = Objects.requireNonNull(socketPath, "socketPath")
                .toAbsolutePath()
                .normalize();
    }

    /** @return {@link TransportKind#UDS} */
    @Override
    public TransportKind kind() {
        return TransportKind.UDS;
    }

    /**
     * 建立 UDS 连接。
     *
     * @return RPC 连接
     * @throws IOException 平台不支持 UDS 或连接失败
     */
    @Override
    public RpcConnection connect() throws IOException {
        SocketChannel channel = SocketChannel.open(StandardProtocolFamily.UNIX);
        try {
            channel.connect(UnixDomainSocketAddress.of(socketPath));
            return new StreamRpcConnection(
                    Channels.newInputStream(channel), Channels.newOutputStream(channel), new JsonRpcCodec());
        } catch (IOException | RuntimeException failure) {
            try {
                channel.close();
            } catch (IOException closeFailure) {
                failure.addSuppressed(closeFailure);
            }
            throw failure;
        }
    }
}
