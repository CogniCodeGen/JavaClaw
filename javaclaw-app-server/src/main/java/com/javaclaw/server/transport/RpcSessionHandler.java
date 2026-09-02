package com.javaclaw.server.transport;

import java.io.IOException;

import com.javaclaw.protocol.RpcConnection;

/**
 * 为一条本地 Transport 连接创建并运行独立 RPC 会话。
 *
 * <p>Transport 只负责连接所有权和并发上限，不感知 App Server 组合根或具体路由实现。
 */
@FunctionalInterface
public interface RpcSessionHandler {
    /**
     * 在当前线程处理连接，直至客户端断开或会话失败。
     *
     * @param connection 已建立连接；所有权由 Transport 保留
     * @throws IOException 读取或发送 RPC 帧失败
     */
    void serve(RpcConnection connection) throws IOException;
}
