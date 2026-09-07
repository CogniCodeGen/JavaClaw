package com.javaclaw.nativehost.sandbox;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/** 通过固定大小缓冲区双向转发，半关闭只关闭对端输出，避免吞掉 CONNECT 响应尾部。 */
final class SocketRelayPump {
    private SocketRelayPump() {}

    static void connect(SocketChannel first, SocketChannel second, Executor tasks) {
        CompletableFuture<Void> forward = CompletableFuture.runAsync(() -> copy(first, second), tasks);
        CompletableFuture<Void> backward = CompletableFuture.runAsync(() -> copy(second, first), tasks);
        try {
            CompletableFuture.allOf(forward, backward).join();
        } finally {
            close(first);
            close(second);
        }
    }

    private static void copy(SocketChannel source, SocketChannel target) {
        ByteBuffer buffer = ByteBuffer.allocate(16 * 1024);
        try {
            while (source.read(buffer) >= 0) {
                buffer.flip();
                while (buffer.hasRemaining()) {
                    target.write(buffer);
                }
                buffer.clear();
            }
            target.shutdownOutput();
        } catch (IOException failure) {
            close(source);
            close(target);
        }
    }

    static void close(AutoCloseable resource) {
        try {
            resource.close();
        } catch (Exception ignored) {
            // 租约结束可能同时关闭两个方向，关闭必须幂等。
        }
    }
}
