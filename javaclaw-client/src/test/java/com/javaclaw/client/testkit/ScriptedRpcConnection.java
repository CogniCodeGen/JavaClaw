package com.javaclaw.client.testkit;

import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.function.Function;

import com.javaclaw.protocol.JsonRpcMessage;
import com.javaclaw.protocol.JsonRpcRequest;
import com.javaclaw.protocol.JsonRpcResponse;
import com.javaclaw.protocol.RpcConnection;

/** 为 SDK 测试提供符合阻塞 receive 契约的脚本连接。 */
public final class ScriptedRpcConnection implements RpcConnection {
    private static final int INBOUND_CAPACITY = 16;
    private static final Object CLOSED = new Object();

    private final Function<JsonRpcRequest, JsonRpcResponse> handler;
    private final BlockingQueue<Object> inbound = new ArrayBlockingQueue<>(INBOUND_CAPACITY);
    private volatile IOException closeFailure;
    private volatile boolean closed;

    /**
     * 创建按 request 生成 response 的连接。
     *
     * @param handler 同步脚本
     */
    public ScriptedRpcConnection(Function<JsonRpcRequest, JsonRpcResponse> handler) {
        this.handler = Objects.requireNonNull(handler, "handler");
    }

    @Override
    public void send(JsonRpcMessage message) throws IOException {
        requireOpen();
        offer(handler.apply((JsonRpcRequest) message));
    }

    @Override
    public JsonRpcMessage receive() throws IOException {
        try {
            Object message = inbound.take();
            if (message == CLOSED) {
                throw new IOException("脚本连接已关闭");
            }
            return (JsonRpcMessage) message;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("脚本连接读取被中断", interrupted);
        }
    }

    /**
     * 在没有 request 时向 reader 推送一条 notification。
     *
     * @param message 通知
     */
    public void emit(JsonRpcMessage message) throws IOException {
        requireOpen();
        offer(Objects.requireNonNull(message, "message"));
    }

    /**
     * 配置 close 失败。
     *
     * @param failure 关闭错误；为空表示清除
     */
    public void closeFailure(IOException failure) {
        closeFailure = failure;
    }

    /**
     * 返回底层连接是否收到关闭请求。
     *
     * @return 关闭状态
     */
    public boolean closed() {
        return closed;
    }

    @Override
    public void close() throws IOException {
        if (closed) {
            return;
        }
        closed = true;
        inbound.clear();
        inbound.offer(CLOSED);
        if (closeFailure != null) {
            throw closeFailure;
        }
    }

    private void offer(JsonRpcMessage message) throws IOException {
        if (!inbound.offer(message)) {
            throw new IOException("脚本连接的有界入站队列已满");
        }
    }

    private void requireOpen() throws IOException {
        if (closed) {
            throw new IOException("脚本连接已关闭");
        }
    }
}
