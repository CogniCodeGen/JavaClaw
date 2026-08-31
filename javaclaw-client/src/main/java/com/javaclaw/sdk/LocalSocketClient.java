package com.javaclaw.sdk;

import java.io.IOException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.Channels;
import java.nio.channels.SocketChannel;
import java.nio.file.Path;
import java.util.Objects;

/** Connects the SDK to a current-user Unix-domain App Server endpoint. */
public final class LocalSocketClient {
    private LocalSocketClient() {}

    /**
     * 连接当前用户的 UDS App Server 并启用自动重连；调用方负责 initialize 和 close。
     *
     * @throws java.io.IOException 无法建立本地连接
     */
    public static JavaClawClient connect(Path socketPath) throws IOException {
        Path path = Objects.requireNonNull(socketPath, "socketPath")
                .toAbsolutePath()
                .normalize();
        return new JavaClawClient(new ReconnectingJsonRpcConnection(() -> open(path)));
    }

    private static JsonRpcConnection open(Path path) throws IOException {
        SocketChannel channel = SocketChannel.open(StandardProtocolFamily.UNIX);
        try {
            channel.connect(UnixDomainSocketAddress.of(path));
            return new JsonRpcConnection(Channels.newInputStream(channel), Channels.newOutputStream(channel));
        } catch (Throwable failure) {
            try {
                channel.close();
            } catch (IOException closeFailure) {
                failure.addSuppressed(closeFailure);
            }
            if (failure instanceof IOException io) {
                throw io;
            }
            if (failure instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new IOException("cannot connect to local App Server", failure);
        }
    }
}
