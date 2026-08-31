package com.javaclaw.server.transport;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Iterator;
import java.util.Objects;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import com.javaclaw.protocol.JsonRpcCodec;
import com.javaclaw.protocol.JsonRpcFrame;
import com.javaclaw.protocol.JsonRpcNotification;
import com.javaclaw.protocol.JsonRpcResponse;
import com.javaclaw.protocol.RpcMethods;

/**
 * Per-connection non-blocking notification queue. A slow client can never run writes on an Agent/event-publisher
 * thread. Delta frames are disposable; durable lifecycle events are not.
 */
final class BoundedJsonlSender implements AutoCloseable {
    static final int MAX_NOTIFICATIONS = 1_024;
    static final long MAX_NOTIFICATION_BYTES = 8L * 1024L * 1024L;
    private static final int MAX_RESPONSES = 1_024;
    private static final long MAX_RESPONSE_BYTES = 8L * 1024L * 1024L;

    private final Writer output;
    private final JsonRpcCodec codec;
    private final Runnable abortTransport;
    private final Object gate = new Object();
    private final ArrayDeque<QueuedFrame> queue = new ArrayDeque<>();
    private final Thread writer;
    private int notificationCount;
    private long notificationBytes;
    private int responseCount;
    private long responseBytes;
    private boolean resyncSignalled;
    private boolean closing;
    private boolean aborted;

    BoundedJsonlSender(Writer output, JsonRpcCodec codec, Runnable abortTransport) {
        this.output = Objects.requireNonNull(output, "output");
        this.codec = Objects.requireNonNull(codec, "codec");
        this.abortTransport = Objects.requireNonNull(abortTransport, "abortTransport");
        this.writer = Thread.ofVirtual().name("javaclaw-jsonrpc-writer").start(this::writeLoop);
    }

    void sendNotification(JsonRpcNotification notification) {
        Objects.requireNonNull(notification, "notification");
        QueuedFrame frame = encode(notification, true, RpcMethods.ITEM_DELTA.equals(notification.method()));
        boolean abort = false;
        // 发布线程只维护有界队列，不写 socket。优先丢弃可重建的 delta，无法保留持久通知时关闭连接。
        synchronized (gate) {
            if (closing) {
                return;
            }
            boolean discarded = discardDeltasUntilFits(frame.bytes());
            if (!fitsNotification(frame.bytes())) {
                discarded = true;
                if (!frame.delta()) {
                    abort = true;
                }
            } else {
                add(frame);
            }
            if (discarded && !abort) {
                abort = !enqueueResync(notification);
            }
            if (abort) {
                abortLocked();
            }
            gate.notifyAll();
        }
        if (abort) {
            abortTransport();
        }
    }

    void sendResponse(JsonRpcResponse response) {
        Objects.requireNonNull(response, "response");
        QueuedFrame frame = encode(response, false, false);
        boolean abort = false;
        synchronized (gate) {
            if (closing) {
                throw new TransportClosedException("JSON-RPC transport is closed");
            }
            if (responseCount >= MAX_RESPONSES || responseBytes + frame.bytes() > MAX_RESPONSE_BYTES) {
                abortLocked();
                abort = true;
            } else {
                add(frame);
                gate.notifyAll();
            }
        }
        if (abort) {
            abortTransport();
            throw new TransportClosedException("JSON-RPC response queue exceeded its limit");
        }
    }

    boolean isAborted() {
        synchronized (gate) {
            return aborted;
        }
    }

    private boolean discardDeltasUntilFits(long incomingBytes) {
        boolean discarded = false;
        if (fitsNotification(incomingBytes)) {
            return false;
        }
        Iterator<QueuedFrame> frames = queue.iterator();
        while (frames.hasNext() && !fitsNotification(incomingBytes)) {
            QueuedFrame queued = frames.next();
            if (!queued.delta()) {
                continue;
            }
            frames.remove();
            notificationCount--;
            notificationBytes -= queued.bytes();
            discarded = true;
        }
        return discarded;
    }

    private boolean enqueueResync(JsonRpcNotification cause) {
        // 每个连接只发一次恢复提示，避免慢客户端因恢复提示本身产生新的无界积压。
        if (resyncSignalled) {
            return true;
        }
        ObjectNode params = JsonNodeFactory.instance.objectNode();
        if (cause.params() != null && cause.params().hasNonNull("threadId")) {
            params.set("threadId", cause.params().get("threadId"));
        }
        params.put(
                "afterSequence",
                cause.params() == null
                        ? 0
                        : Math.max(0, cause.params().path("afterSequence").asLong(0)));
        params.put("reason", "slowClient");
        QueuedFrame resync = encode(new JsonRpcNotification("2.0", RpcMethods.RESYNC_REQUIRED, params), true, false);
        discardDeltasUntilFits(resync.bytes());
        if (!fitsNotification(resync.bytes())) {
            return false;
        }
        add(resync);
        resyncSignalled = true;
        return true;
    }

    private boolean fitsNotification(long bytes) {
        return notificationCount < MAX_NOTIFICATIONS && notificationBytes + bytes <= MAX_NOTIFICATION_BYTES;
    }

    private void add(QueuedFrame frame) {
        queue.addLast(frame);
        if (frame.notification()) {
            notificationCount++;
            notificationBytes += frame.bytes();
        } else {
            responseCount++;
            responseBytes += frame.bytes();
        }
    }

    private QueuedFrame take() throws InterruptedException {
        synchronized (gate) {
            while (queue.isEmpty() && !closing) {
                gate.wait();
            }
            if (queue.isEmpty()) {
                return null;
            }
            QueuedFrame frame = queue.removeFirst();
            if (frame.notification()) {
                notificationCount--;
                notificationBytes -= frame.bytes();
            } else {
                responseCount--;
                responseBytes -= frame.bytes();
            }
            return frame;
        }
    }

    private void writeLoop() {
        // 独立写线程承担阻塞 I/O；取帧时持锁，实际写入时已释放锁，不能把背压传回 Agent。
        try {
            QueuedFrame frame;
            while ((frame = take()) != null) {
                output.write(frame.encoded());
                output.flush();
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (IOException failure) {
            boolean shouldAbort;
            synchronized (gate) {
                shouldAbort = !closing;
                abortLocked();
            }
            if (shouldAbort) {
                abortTransport();
            }
        }
    }

    private QueuedFrame encode(JsonRpcFrame frame, boolean notification, boolean delta) {
        try {
            String encoded = codec.encode(frame) + '\n';
            return new QueuedFrame(encoded, encoded.getBytes(StandardCharsets.UTF_8).length, notification, delta);
        } catch (com.fasterxml.jackson.core.JsonProcessingException failure) {
            throw new IllegalStateException("cannot encode JSON-RPC frame", failure);
        }
    }

    private void abortLocked() {
        aborted = true;
        closing = true;
        queue.clear();
        notificationCount = 0;
        notificationBytes = 0;
        responseCount = 0;
        responseBytes = 0;
        gate.notifyAll();
    }

    private void abortTransport() {
        try {
            abortTransport.run();
        } catch (RuntimeException ignored) {
        }
    }

    @Override
    public void close() {
        synchronized (gate) {
            closing = true;
            gate.notifyAll();
        }
        try {
            writer.join(5_000);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        if (writer.isAlive()) {
            writer.interrupt();
        }
    }

    private record QueuedFrame(String encoded, long bytes, boolean notification, boolean delta) {}

    static final class TransportClosedException extends RuntimeException {
        TransportClosedException(String message) {
            super(message);
        }
    }
}
