package com.javaclaw.protocol;

import java.io.IOException;

/** 可替换的本地 Transport SPI；5.0 不提供 TCP 或 WebSocket 实现。 */
public interface LocalTransport {
    /**
     * 返回 Transport 类别。
     *
     * @return stdio、UDS 或 Named Pipe
     */
    TransportKind kind();

    /**
     * 建立客户端连接。
     *
     * @return RPC 连接
     * @throws IOException 连接失败
     */
    RpcConnection connect() throws IOException;
}
