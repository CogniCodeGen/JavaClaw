package com.javaclaw.protocol;

import java.io.IOException;

/** 有序、全双工的本地 RPC 连接。 */
public interface RpcConnection extends AutoCloseable {
    /**
     * 发送一条完整消息。
     *
     * @param message 消息
     * @throws IOException 连接失败
     */
    void send(JsonRpcMessage message) throws IOException;

    /**
     * 阻塞读取下一条完整消息。
     *
     * @return 消息
     * @throws IOException 连接关闭或帧损坏
     */
    JsonRpcMessage receive() throws IOException;

    /**
     * 关闭连接并唤醒阻塞读写。
     *
     * @throws IOException 关闭失败
     */
    @Override
    void close() throws IOException;
}
