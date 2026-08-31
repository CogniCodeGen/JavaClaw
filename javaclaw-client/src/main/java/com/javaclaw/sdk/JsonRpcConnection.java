package com.javaclaw.sdk;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.NullNode;

import com.javaclaw.protocol.JsonRpcCodec;
import com.javaclaw.protocol.JsonRpcFrame;
import com.javaclaw.protocol.JsonRpcNotification;
import com.javaclaw.protocol.JsonRpcRequest;
import com.javaclaw.protocol.JsonRpcResponse;

/** Concurrent JSONL client. One reader dispatches responses and notifications independently. */
final class JsonRpcConnection implements RpcConnection {
    static final int DEFAULT_MAX_FRAME_CHARS = 8 * 1024 * 1024;

    private final JsonRpcCodec codec;
    private final BufferedReader input;
    private final BufferedWriter output;
    private final int maxFrameChars;
    private final ExecutorService reader = java.util.concurrent.Executors.newSingleThreadExecutor(
            Thread.ofVirtual().name("javaclaw-sdk-reader").factory());
    private final ConcurrentHashMap<Long, CompletableFuture<JsonNode>> pending = new ConcurrentHashMap<>();
    private final List<Consumer<ServerNotification>> listeners = new CopyOnWriteArrayList<>();
    private final AtomicLong requestIds = new AtomicLong();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final CompletableFuture<Throwable> termination = new CompletableFuture<>();

    JsonRpcConnection(InputStream input, OutputStream output) {
        this(input, output, new JsonRpcCodec(), DEFAULT_MAX_FRAME_CHARS);
    }

    JsonRpcConnection(InputStream input, OutputStream output, JsonRpcCodec codec) {
        this(input, output, codec, DEFAULT_MAX_FRAME_CHARS);
    }

    JsonRpcConnection(InputStream input, OutputStream output, JsonRpcCodec codec, int maxFrameChars) {
        this.codec = Objects.requireNonNull(codec, "codec");
        if (maxFrameChars < 1) {
            throw new IllegalArgumentException("maxFrameChars must be positive");
        }
        this.maxFrameChars = maxFrameChars;
        this.input = new BufferedReader(
                new InputStreamReader(Objects.requireNonNull(input, "input"), StandardCharsets.UTF_8));
        this.output = new BufferedWriter(
                new OutputStreamWriter(Objects.requireNonNull(output, "output"), StandardCharsets.UTF_8));
        reader.submit(this::readLoop);
    }

    public CompletableFuture<JsonNode> request(String method, JsonNode params) {
        requireOpen();
        long id = requestIds.incrementAndGet();
        CompletableFuture<JsonNode> result = new CompletableFuture<>();
        pending.put(id, result);
        if (closed.get() && pending.remove(id, result)) {
            result.completeExceptionally(new IOException("connection closed"));
            return result;
        }
        try {
            send(new JsonRpcRequest(
                    "2.0",
                    codec.mapper().getNodeFactory().numberNode(id),
                    method,
                    params == null ? NullNode.getInstance() : params));
        } catch (RuntimeException failure) {
            pending.remove(id);
            result.completeExceptionally(failure);
        }
        return result;
    }

    public void notify(String method, JsonNode params) {
        requireOpen();
        send(new JsonRpcNotification("2.0", method, params == null ? NullNode.getInstance() : params));
    }

    public AutoCloseable onNotification(Consumer<ServerNotification> listener) {
        Objects.requireNonNull(listener, "listener");
        listeners.add(listener);
        return () -> listeners.remove(listener);
    }

    public JsonRpcCodec codec() {
        return codec;
    }

    boolean isClosed() {
        return closed.get();
    }

    CompletableFuture<Throwable> termination() {
        return termination;
    }

    private void readLoop() {
        Throwable terminal = null;
        try {
            String line;
            while (!closed.get() && (line = readBoundedLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                dispatch(codec.decode(line));
            }
            if (!closed.get()) {
                terminal = new IOException("app server closed the connection");
            }
        } catch (Throwable failure) {
            terminal = failure;
        } finally {
            if (terminal != null) {
                terminate(terminal);
            }
        }
    }

    private String readBoundedLine() throws IOException {
        StringBuilder line = new StringBuilder(Math.min(maxFrameChars, 4096));
        int value;
        while ((value = input.read()) >= 0) {
            if (value == '\n') {
                break;
            }
            if (value != '\r') {
                line.append((char) value);
            }
            if (line.length() > maxFrameChars) {
                throw new IOException("JSON-RPC frame exceeds " + maxFrameChars + " characters");
            }
        }
        if (value < 0 && line.isEmpty()) {
            return null;
        }
        return line.toString();
    }

    private void dispatch(JsonRpcFrame frame) {
        if (frame instanceof JsonRpcNotification notification) {
            ServerNotification value = new ServerNotification(notification.method(), notification.params());
            listeners.forEach(listener -> {
                try {
                    listener.accept(value);
                } catch (RuntimeException ignored) {
                }
            });
            return;
        }
        if (!(frame instanceof JsonRpcResponse response) || !response.id().isIntegralNumber()) {
            return;
        }
        CompletableFuture<JsonNode> target = pending.remove(response.id().longValue());
        if (target == null) {
            return;
        }
        if (response.error() == null) {
            target.complete(response.result());
        } else {
            target.completeExceptionally(new RpcException(
                    response.error().code(),
                    response.error().message(),
                    response.error().data()));
        }
    }

    private synchronized void send(JsonRpcFrame frame) {
        try {
            output.write(codec.encode(frame));
            output.newLine();
            output.flush();
        } catch (IOException failure) {
            throw new IllegalStateException("cannot write to app server", failure);
        }
    }

    private void failPending(Throwable failure) {
        pending.forEach((id, future) -> future.completeExceptionally(failure));
        pending.clear();
    }

    private void terminate(Throwable failure) {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        closeStreams();
        failPending(failure);
        termination.complete(failure);
        reader.shutdown();
    }

    private void requireOpen() {
        if (closed.get()) {
            throw new IllegalStateException("connection is closed");
        }
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        // Close the request side first so a stdio server observes EOF and releases its
        // response writer. Closing a blocking response channel first can deadlock.
        closeStreams();
        reader.shutdownNow();
        IOException failure = new IOException("connection closed");
        failPending(failure);
        termination.complete(failure);
    }

    private synchronized void closeStreams() {
        try {
            output.close();
        } catch (IOException ignored) {
        }
        try {
            input.close();
        } catch (IOException ignored) {
        }
    }
}
