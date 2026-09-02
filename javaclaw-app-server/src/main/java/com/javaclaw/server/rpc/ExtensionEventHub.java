package com.javaclaw.server.rpc;

import java.io.IOException;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.javaclaw.protocol.CanonicalJson;
import com.javaclaw.protocol.ExtensionRpcContracts;
import com.javaclaw.protocol.JsonRpcNotification;
import com.javaclaw.protocol.RpcConnection;

/**
 * 向已完成 initialize 的本地客户端广播有界 Extension 状态失效通知。
 *
 * <p>每条连接拥有固定容量队列和单个虚拟线程，慢客户端不会阻塞 Extension 事务或占用无界内存。队列溢出或发送失败时关闭该连接， 客户端重连后必须重新读取权威状态。
 */
public final class ExtensionEventHub implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger(ExtensionEventHub.class);
    private static final int QUEUE_CAPACITY = 64;

    private final CanonicalJson json;
    private final Set<EventStream> streams = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean closed = new AtomicBoolean();

    /**
     * 创建通知中心。
     *
     * @param json 规范 JSON codec
     */
    public ExtensionEventHub(CanonicalJson json) {
        this.json = Objects.requireNonNull(json, "json");
    }

    Subscription subscribe(RpcConnection connection) {
        if (closed.get()) {
            throw new IllegalStateException("Extension event hub is closed");
        }
        EventStream stream = new EventStream(Objects.requireNonNull(connection, "connection"));
        streams.add(stream);
        if (closed.get()) {
            stream.abort();
            throw new IllegalStateException("Extension event hub is closed");
        }
        stream.start();
        return stream;
    }

    void publish(ExtensionRpcContracts.ExtensionEvent event) {
        if (closed.get()) {
            return;
        }
        JsonRpcNotification notification =
                new JsonRpcNotification("extension/event", json.encode(Objects.requireNonNull(event, "event")));
        streams.forEach(stream -> stream.offer(notification));
    }

    /** 停止全部通知线程并断开现有连接。 */
    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            streams.forEach(EventStream::abort);
            streams.clear();
        }
    }

    interface Subscription extends AutoCloseable {
        /** 停止当前连接的通知流，但不关闭由会话持有的连接。 */
        @Override
        void close();
    }

    private final class EventStream implements Subscription {
        private final RpcConnection connection;
        private final ArrayBlockingQueue<JsonRpcNotification> queue = new ArrayBlockingQueue<>(QUEUE_CAPACITY);
        private final AtomicBoolean streamClosed = new AtomicBoolean();
        private volatile Thread writer;

        private EventStream(RpcConnection connection) {
            this.connection = connection;
        }

        private void start() {
            writer = Thread.ofVirtual().name("javaclaw-extension-events").start(this::writeLoop);
        }

        private void offer(JsonRpcNotification notification) {
            if (!streamClosed.get() && !queue.offer(notification)) {
                LOGGER.warn("Extension event queue reached its bounded capacity; disconnecting slow client");
                abort();
            }
        }

        private void writeLoop() {
            while (!streamClosed.get()) {
                try {
                    JsonRpcNotification notification = queue.poll(100, TimeUnit.MILLISECONDS);
                    if (notification != null) {
                        connection.send(notification);
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                } catch (IOException failure) {
                    LOGGER.debug("Extension event connection is no longer writable", failure);
                    abort();
                }
            }
        }

        private void abort() {
            close();
            try {
                connection.close();
            } catch (IOException failure) {
                LOGGER.debug("Extension event connection close failed", failure);
            }
        }

        @Override
        public void close() {
            if (!streamClosed.compareAndSet(false, true)) {
                return;
            }
            streams.remove(this);
            Thread currentWriter = writer;
            if (currentWriter != null && currentWriter != Thread.currentThread()) {
                currentWriter.interrupt();
            }
            queue.clear();
        }
    }
}
