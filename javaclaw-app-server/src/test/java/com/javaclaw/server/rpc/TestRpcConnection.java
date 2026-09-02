package com.javaclaw.server.rpc;

import java.io.EOFException;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.List;

import com.javaclaw.protocol.JsonRpcMessage;
import com.javaclaw.protocol.RpcConnection;

/** 为会话测试提供确定性的内存连接与传输失败注入。 */
final class TestRpcConnection implements RpcConnection {
    private final ArrayDeque<JsonRpcMessage> incoming = new ArrayDeque<>();
    private final List<JsonRpcMessage> sent = new java.util.concurrent.CopyOnWriteArrayList<>();
    private final IOException receiveFailure;

    TestRpcConnection(JsonRpcMessage... messages) {
        this(null, messages);
    }

    private TestRpcConnection(IOException receiveFailure, JsonRpcMessage... messages) {
        this.receiveFailure = receiveFailure;
        incoming.addAll(List.of(messages));
    }

    static TestRpcConnection failing() {
        return new TestRpcConnection(new IOException("transport failed"));
    }

    List<JsonRpcMessage> sent() {
        return List.copyOf(sent);
    }

    @Override
    public void send(JsonRpcMessage message) {
        sent.add(message);
    }

    @Override
    public JsonRpcMessage receive() throws IOException {
        if (receiveFailure != null) {
            throw receiveFailure;
        }
        if (incoming.isEmpty()) {
            throw new EOFException();
        }
        return incoming.removeFirst();
    }

    @Override
    public void close() {}
}
