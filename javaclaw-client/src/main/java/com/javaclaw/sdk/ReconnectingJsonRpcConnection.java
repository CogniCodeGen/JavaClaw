package com.javaclaw.sdk;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import com.javaclaw.protocol.JsonRpcCodec;
import com.javaclaw.protocol.RpcMethods;

/**
 * Reconnecting SDK transport. Only requests carrying an idempotency key are replayed after an ambiguous disconnect; new
 * read requests can be issued once the connection is healthy again.
 */
final class ReconnectingJsonRpcConnection implements RpcConnection {
    @FunctionalInterface
    interface ConnectionFactory {
        JsonRpcConnection connect() throws IOException;
    }

    private static final long INITIAL_BACKOFF_MILLIS = 100;
    private static final long MAX_BACKOFF_MILLIS = 5_000;

    private final ConnectionFactory factory;
    private final JsonRpcCodec codec;
    private final Object gate = new Object();
    private final CopyOnWriteArrayList<Consumer<ServerNotification>> notificationListeners =
            new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<Consumer<ConnectionStatus>> stateListeners = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<Consumer<ProtocolRecovery>> recoveryListeners = new CopyOnWriteArrayList<>();
    private final ConcurrentHashMap<String, AtomicLong> cursors = new ConcurrentHashMap<>();
    private final AtomicBoolean closed = new AtomicBoolean();
    private volatile JsonRpcConnection current;
    private volatile CompletableFuture<JsonRpcConnection> reconnecting;
    private volatile Thread reconnectWorker;
    private volatile JsonNode initializeParams;
    private volatile ConnectionStatus status =
            new ConnectionStatus(ConnectionStatus.State.CONNECTED, 0, "", Instant.now());

    ReconnectingJsonRpcConnection(ConnectionFactory factory) throws IOException {
        this.factory = Objects.requireNonNull(factory, "factory");
        JsonRpcConnection initial = factory.connect();
        this.codec = initial.codec();
        install(initial);
    }

    @Override
    public CompletableFuture<JsonNode> request(String method, JsonNode params) {
        Objects.requireNonNull(method, "method");
        if (closed.get()) {
            return CompletableFuture.failedFuture(new IOException("connection is closed"));
        }
        JsonNode safeParams = params == null ? JsonNodeFactory.instance.nullNode() : params.deepCopy();
        if (RpcMethods.INITIALIZE.equals(method)) {
            initializeParams = safeParams.deepCopy();
        }
        boolean replayable = hasIdempotencyKey(safeParams);
        JsonRpcConnection connection = current;
        if (connection == null || connection.isClosed()) {
            if (!replayable) {
                return CompletableFuture.failedFuture(new IOException(
                        "connection is reconnecting; request was not sent because it has no idempotencyKey"));
            }
            return reconnect().thenCompose(value -> requestWithReplay(value, method, safeParams, true));
        }
        return requestWithReplay(connection, method, safeParams, replayable);
    }

    private CompletableFuture<JsonNode> requestWithReplay(
            JsonRpcConnection connection, String method, JsonNode params, boolean replayable) {
        CompletableFuture<JsonNode> request;
        try {
            request = connection.request(method, params);
        } catch (RuntimeException failure) {
            request = CompletableFuture.failedFuture(failure);
        }
        return request.handle((result, failure) -> {
                    if (failure == null) {
                        observeResponse(method, params, result);
                        return CompletableFuture.completedFuture(result);
                    }
                    Throwable cause = unwrap(failure);
                    if (!replayable || cause instanceof RpcException || closed.get()) {
                        return CompletableFuture.<JsonNode>failedFuture(cause);
                    }
                    connectionLost(connection, cause);
                    return reconnect().thenCompose(next -> requestWithReplay(next, method, params, true));
                })
                .thenCompose(value -> value);
    }

    @Override
    public void notify(String method, JsonNode params) {
        JsonRpcConnection connection = current;
        if (closed.get() || connection == null || connection.isClosed()) {
            throw new IllegalStateException("connection is reconnecting or closed");
        }
        connection.notify(method, params);
    }

    @Override
    public AutoCloseable onNotification(Consumer<ServerNotification> listener) {
        Objects.requireNonNull(listener, "listener");
        notificationListeners.add(listener);
        return () -> notificationListeners.remove(listener);
    }

    @Override
    public AutoCloseable onConnectionState(Consumer<ConnectionStatus> listener) {
        Objects.requireNonNull(listener, "listener");
        stateListeners.add(listener);
        listener.accept(status);
        return () -> stateListeners.remove(listener);
    }

    @Override
    public AutoCloseable onRecoveredThread(Consumer<ProtocolRecovery> listener) {
        Objects.requireNonNull(listener, "listener");
        recoveryListeners.add(listener);
        return () -> recoveryListeners.remove(listener);
    }

    @Override
    public JsonRpcCodec codec() {
        return codec;
    }

    private void install(JsonRpcConnection connection) {
        current = connection;
        connection.onNotification(this::acceptNotification);
        connection.termination().thenAccept(failure -> connectionLost(connection, failure));
        publishState(ConnectionStatus.State.CONNECTED, 0, "");
    }

    private void connectionLost(JsonRpcConnection connection, Throwable failure) {
        synchronized (gate) {
            if (closed.get() || current != connection) {
                return;
            }
            current = null;
            startReconnectLocked(failure);
        }
    }

    private CompletableFuture<JsonRpcConnection> reconnect() {
        synchronized (gate) {
            if (closed.get()) {
                return CompletableFuture.failedFuture(new IOException("connection is closed"));
            }
            JsonRpcConnection connected = current;
            if (connected != null && !connected.isClosed()) {
                return CompletableFuture.completedFuture(connected);
            }
            return startReconnectLocked(new IOException("connection is unavailable"));
        }
    }

    private CompletableFuture<JsonRpcConnection> startReconnectLocked(Throwable cause) {
        CompletableFuture<JsonRpcConnection> existing = reconnecting;
        if (existing != null && !existing.isDone()) {
            return existing;
        }
        CompletableFuture<JsonRpcConnection> result = new CompletableFuture<>();
        reconnecting = result;
        reconnectWorker = Thread.ofVirtual().name("javaclaw-sdk-reconnect").start(() -> reconnectLoop(result, cause));
        return result;
    }

    private void reconnectLoop(CompletableFuture<JsonRpcConnection> target, Throwable originalFailure) {
        long backoff = INITIAL_BACKOFF_MILLIS;
        int attempt = 0;
        Throwable last = originalFailure;
        while (!closed.get()) {
            attempt++;
            publishState(ConnectionStatus.State.RECONNECTING, attempt, message(last));
            JsonRpcConnection candidate = null;
            try {
                candidate = factory.connect();
                restoreProtocolAndThreads(candidate);
                synchronized (gate) {
                    if (closed.get()) {
                        candidate.close();
                        target.completeExceptionally(new IOException("connection is closed"));
                        return;
                    }
                    install(candidate);
                    reconnecting = null;
                }
                target.complete(candidate);
                return;
            } catch (Throwable failure) {
                last = unwrap(failure);
                if (candidate != null) {
                    candidate.close();
                }
                try {
                    Thread.sleep(backoff);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    target.completeExceptionally(new IOException("reconnect was interrupted", interrupted));
                    return;
                }
                backoff = Math.min(MAX_BACKOFF_MILLIS, backoff * 2);
            }
        }
        target.completeExceptionally(new IOException("connection is closed", last));
    }

    private void restoreProtocolAndThreads(JsonRpcConnection connection) {
        JsonNode initialization = initializeParams;
        if (initialization != null) {
            connection.request(RpcMethods.INITIALIZE, initialization).join();
            connection.notify(RpcMethods.INITIALIZED, JsonNodeFactory.instance.objectNode());
        }
        List<MapEntry> subscriptions = new ArrayList<>();
        cursors.forEach((threadId, sequence) -> subscriptions.add(new MapEntry(threadId, sequence.get())));
        for (MapEntry subscription : subscriptions) {
            ObjectNode params = JsonNodeFactory.instance.objectNode();
            params.put("threadId", subscription.threadId());
            params.put("afterSequence", subscription.afterSequence());
            JsonNode result =
                    connection.request(RpcMethods.THREAD_RESUME, params).join();
            observeResponse(RpcMethods.THREAD_RESUME, params, result);
            ProtocolRecovery recovered = new ProtocolRecovery(
                    subscription.threadId(), result.get("snapshot"), result.get("events"), result.get("liveItems"));
            recoveryListeners.forEach(listener -> safeAccept(listener, recovered));
        }
    }

    private void acceptNotification(ServerNotification notification) {
        JsonNode params = notification.params();
        JsonNode event = params == null ? null : params.get("event");
        if (event != null && event.isObject()) {
            updateCursor(
                    event.path("threadId").asText(""), event.path("sequence").asLong(0));
        }
        if (RpcMethods.RESYNC_REQUIRED.equals(notification.method())) {
            publishState(
                    ConnectionStatus.State.RESYNC_REQUIRED,
                    0,
                    params == null ? "" : params.path("reason").asText("durable replay required"));
        }
        notificationListeners.forEach(listener -> safeAccept(listener, notification));
    }

    private void observeResponse(String method, JsonNode params, JsonNode result) {
        if (RpcMethods.THREAD_START.equals(method) || RpcMethods.THREAD_FORK.equals(method)) {
            updateCursor(
                    result.path("id").asText(""), result.path("lastSequence").asLong(0));
        } else if (RpcMethods.THREAD_RESUME.equals(method)) {
            JsonNode thread = result.path("snapshot").path("thread");
            updateCursor(
                    thread.path("id").asText(""), thread.path("lastSequence").asLong(0));
        } else if (RpcMethods.TURN_START.equals(method)) {
            updateCursor(params.path("threadId").asText(""), 0);
        } else if (RpcMethods.THREAD_DELETE.equals(method)) {
            cursors.remove(params.path("threadId").asText(""));
        }
    }

    private void updateCursor(String threadId, long sequence) {
        if (threadId == null || threadId.isBlank()) {
            return;
        }
        cursors.computeIfAbsent(threadId, ignored -> new AtomicLong())
                .accumulateAndGet(Math.max(0, sequence), Math::max);
    }

    private void publishState(ConnectionStatus.State state, int attempt, String detail) {
        ConnectionStatus next = new ConnectionStatus(state, attempt, detail, Instant.now());
        status = next;
        stateListeners.forEach(listener -> safeAccept(listener, next));
    }

    private static boolean hasIdempotencyKey(JsonNode params) {
        return params != null
                && params.isObject()
                && params.path("idempotencyKey").isTextual()
                && !params.path("idempotencyKey").textValue().isBlank();
    }

    private static Throwable unwrap(Throwable failure) {
        Throwable result = failure;
        while ((result instanceof CompletionException || result instanceof java.util.concurrent.ExecutionException)
                && result.getCause() != null) {
            result = result.getCause();
        }
        return result;
    }

    private static String message(Throwable failure) {
        if (failure == null) {
            return "connection lost";
        }
        return failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
    }

    private static <T> void safeAccept(Consumer<T> listener, T value) {
        try {
            listener.accept(value);
        } catch (RuntimeException ignored) {
        }
    }

    @Override
    public void close() {
        closeAfterStoppingTransport(() -> {});
    }

    void closeAfterStoppingTransport(Runnable stopOwnedTransport) {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        Thread worker = reconnectWorker;
        if (worker != null) {
            worker.interrupt();
        }
        JsonRpcConnection connection = current;
        current = null;
        CompletableFuture<JsonRpcConnection> pending = reconnecting;
        if (pending != null) {
            pending.completeExceptionally(new IOException("connection is closed"));
        }
        publishState(ConnectionStatus.State.CLOSED, 0, "");
        notificationListeners.clear();
        recoveryListeners.clear();
        stateListeners.clear();
        // 先撤销重连，再停止所拥有的进程/传输，最后关闭可能正被 reader 占用的流锁。
        // 正常退出不能被误报为崩溃，更不能在 Supervisor 关闭期间重新创建 App Server。
        try {
            stopOwnedTransport.run();
        } finally {
            if (connection != null) {
                connection.close();
            }
        }
    }

    private record MapEntry(String threadId, long afterSequence) {}
}
