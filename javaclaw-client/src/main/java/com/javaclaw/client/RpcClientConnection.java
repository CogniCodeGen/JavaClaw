package com.javaclaw.client;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import com.javaclaw.api.CanonicalPayload;
import com.javaclaw.protocol.CanonicalJson;
import com.javaclaw.protocol.JsonRpcMessage;
import com.javaclaw.protocol.JsonRpcNotification;
import com.javaclaw.protocol.JsonRpcRequest;
import com.javaclaw.protocol.JsonRpcResponse;
import com.javaclaw.protocol.RpcConnection;
import com.javaclaw.protocol.RpcId;
import com.javaclaw.protocol.WriteCommand;

/**
 * 串行发送 request、持续接收 response 与 notification 的 SDK 连接核心。
 *
 * <p>同一连接最多只有一个在途 request，因此无需无界 pending map。独占虚拟线程始终排空服务端通知，即使客户端暂时没有发起调用，也不会让服务端通知队列反压到断线。
 *
 * <p><strong>实现说明：</strong>notification 消费者必须在固定时限内返回；超时或异常会关闭连接，避免客户端丢失失效通知后继续使用陈旧状态。
 */
public final class RpcClientConnection implements AutoCloseable {
    private static final long NOTIFICATION_TIMEOUT_SECONDS = 1;

    private final RpcConnection connection;
    private final CanonicalJson json;
    private final Consumer<JsonRpcNotification> notifications;
    private final AtomicLong nextId = new AtomicLong();
    private final AtomicReference<PendingCall> pending = new AtomicReference<>();
    private final AtomicReference<IOException> terminalFailure = new AtomicReference<>();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Object callLock = new Object();
    private final java.util.concurrent.CopyOnWriteArrayList<Consumer<JsonRpcNotification>> observers =
            new java.util.concurrent.CopyOnWriteArrayList<>();
    private final java.util.concurrent.CopyOnWriteArrayList<Consumer<IOException>> failures =
            new java.util.concurrent.CopyOnWriteArrayList<>();
    private volatile Thread reader;

    /**
     * 创建连接核心并接管底层连接所有权。
     *
     * <p>调用可来自多个线程，但同一连接上的 request 会有序执行。这样既避免无界 pending map，也确保断线时只有一个不确定命令；命令幂等键用于恢复。
     *
     * @param connection 已连接 RPC
     * @param json 共享 JSON codec
     * @param notifications 服务端通知消费者，必须快速且不得抛出异常
     */
    public RpcClientConnection(
            RpcConnection connection, CanonicalJson json, Consumer<JsonRpcNotification> notifications) {
        this.connection = Objects.requireNonNull(connection, "connection");
        this.json = Objects.requireNonNull(json, "json");
        this.notifications = Objects.requireNonNull(notifications, "notifications");
        reader = Thread.ofVirtual().name("javaclaw-sdk-rpc-reader").start(this::readLoop);
    }

    /**
     * 登记 SDK 内部通知观察者，必须只校验和有界入队，禁止同步 RPC。
     *
     * @param observer 快速通知回调
     * @return 释放登记的句柄
     */
    public AutoCloseable observe(Consumer<JsonRpcNotification> observer) {
        observers.add(Objects.requireNonNull(observer, "observer"));
        return () -> observers.remove(observer);
    }

    /**
     * 登记连接失败观察者，主动通知连接 owner 重建 SDK；回调不得执行阻塞工作。
     *
     * @param observer 失败回调
     * @return 释放登记的句柄
     */
    public AutoCloseable onFailure(Consumer<IOException> observer) {
        failures.add(Objects.requireNonNull(observer, "observer"));
        IOException failure = terminalFailure.get();
        if (failure != null) {
            observer.accept(failure);
        }
        return () -> failures.remove(observer);
    }

    /**
     * 执行 query。
     *
     * @param method 方法
     * @param params 强类型参数
     * @param resultType 结果类型
     * @param <T> 结果类型
     * @return 解码结果
     */
    public <T> T query(String method, Object params, Class<T> resultType) {
        return json.decode(call(method, json.encode(params)), resultType);
    }

    /**
     * 执行带幂等与 revision 的 command。
     *
     * @param method 方法
     * @param payload 业务参数
     * @param options 调用选项
     * @param resultType 结果类型
     * @param <T> 结果类型
     * @return 解码结果
     */
    public <T> T command(String method, Object payload, CommandOptions options, Class<T> resultType) {
        Objects.requireNonNull(options, "options");
        WriteCommand command =
                new WriteCommand(options.idempotencyKey(), options.expectedRevision(), json.encode(payload));
        return query(method, command, resultType);
    }

    private CanonicalPayload call(String method, CanonicalPayload params) {
        synchronized (callLock) {
            ensureUsable();
            RpcId id = new RpcId(Long.toUnsignedString(nextId.incrementAndGet()));
            PendingCall call = new PendingCall(id, new CompletableFuture<>());
            if (!pending.compareAndSet(null, call)) {
                throw new IllegalStateException("同一连接不允许多个在途 request");
            }
            try {
                connection.send(new JsonRpcRequest(id, method, params));
                return result(call);
            } catch (IOException failure) {
                fail(failure);
                throw new UncheckedIOException("local RPC connection failed", failure);
            } finally {
                pending.compareAndSet(call, null);
            }
        }
    }

    private CanonicalPayload result(PendingCall call) {
        JsonRpcResponse response = await(call);
        if (response.error().isPresent()) {
            throw new RemoteRpcException(response.error().orElseThrow());
        }
        return response.result().orElseThrow();
    }

    private JsonRpcResponse await(PendingCall call) {
        try {
            return call.response().get();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            IOException failure = new IOException("等待本地 RPC response 时被中断", interrupted);
            fail(failure);
            throw new UncheckedIOException(failure);
        } catch (ExecutionException failed) {
            Throwable cause = failed.getCause();
            if (cause instanceof IOException transportFailure) {
                throw new UncheckedIOException("local RPC connection failed", transportFailure);
            }
            if (cause instanceof RuntimeException runtimeFailure) {
                throw runtimeFailure;
            }
            throw new IllegalStateException("本地 RPC reader 意外失败", cause);
        }
    }

    private void readLoop() {
        while (!closed.get()) {
            try {
                route(connection.receive());
            } catch (IOException failure) {
                if (!closed.get()) {
                    fail(failure);
                }
                return;
            } catch (RuntimeException failure) {
                fail(new IOException("本地 RPC reader 无法处理消息", failure));
                return;
            }
        }
    }

    private void route(JsonRpcMessage message) throws IOException {
        if (message instanceof JsonRpcNotification notification) {
            deliver(notification);
            return;
        }
        if (!(message instanceof JsonRpcResponse response)) {
            throw new IOException("服务端发送了非 response/notification 消息");
        }
        PendingCall current = pending.get();
        if (current == null || !response.id().equals(current.id())) {
            throw new IOException("收到无法匹配的 JSON-RPC response: " + response.id().value());
        }
        if (!pending.compareAndSet(current, null)) {
            throw new IOException("JSON-RPC response 已被其他 reader 消费");
        }
        current.response().complete(response);
    }

    private void deliver(JsonRpcNotification notification) throws IOException {
        FutureTask<Void> delivery = new FutureTask<>(() -> {
            observers.forEach(observer -> observer.accept(notification));
            notifications.accept(notification);
            return null;
        });
        Thread consumer =
                Thread.ofVirtual().name("javaclaw-sdk-notification-consumer").start(delivery);
        try {
            delivery.get(NOTIFICATION_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (TimeoutException timeout) {
            consumer.interrupt();
            throw new IOException("服务端 notification 消费超时，连接已失败关闭", timeout);
        } catch (InterruptedException interrupted) {
            consumer.interrupt();
            Thread.currentThread().interrupt();
            throw new IOException("服务端 notification 消费被中断", interrupted);
        } catch (ExecutionException failed) {
            throw new IOException("服务端 notification 消费失败", failed.getCause());
        }
    }

    private void ensureUsable() {
        IOException failed = terminalFailure.get();
        if (failed != null) {
            throw new UncheckedIOException("local RPC connection failed", failed);
        }
        if (closed.get()) {
            throw new IllegalStateException("SDK 连接已经关闭");
        }
    }

    private void fail(IOException failure) {
        if (!terminalFailure.compareAndSet(null, Objects.requireNonNull(failure, "failure"))) {
            return;
        }
        PendingCall current = pending.getAndSet(null);
        if (current != null) {
            current.response().completeExceptionally(failure);
        }
        closeTransport();
        failures.forEach(observer -> observer.accept(failure));
    }

    private void closeTransport() {
        try {
            connection.close();
        } catch (IOException closeFailure) {
            IOException first = terminalFailure.get();
            if (first != null && first != closeFailure) {
                first.addSuppressed(closeFailure);
            }
        }
    }

    /** 关闭底层本地连接，并唤醒持续接收通知的 reader。 */
    @Override
    public void close() throws IOException {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        IOException failure = null;
        try {
            connection.close();
        } catch (IOException closeFailure) {
            failure = closeFailure;
        }
        Thread activeReader = reader;
        if (activeReader != null) {
            activeReader.interrupt();
        }
        PendingCall current = pending.getAndSet(null);
        if (current != null) {
            current.response().completeExceptionally(new IOException("SDK 连接已经关闭"));
        }
        if (failure != null) {
            throw failure;
        }
    }

    private record PendingCall(RpcId id, CompletableFuture<JsonRpcResponse> response) {
        private PendingCall {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(response, "response");
        }
    }
}
