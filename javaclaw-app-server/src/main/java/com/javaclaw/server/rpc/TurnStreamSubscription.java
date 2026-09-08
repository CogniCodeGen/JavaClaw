package com.javaclaw.server.rpc;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import com.javaclaw.protocol.CanonicalJson;
import com.javaclaw.protocol.JsonRpcNotification;
import com.javaclaw.protocol.RpcConnection;
import com.javaclaw.protocol.TurnStreamRpcContracts;
import com.javaclaw.server.persistence.TurnStreamService;

/**
 * 每个订阅只有一个日志 drain。唤醒只改变工作标记，发送永远在事务之外且有硬超时。
 *
 * <p>订阅只借用共享 RPC 连接；退订协作式停止，不中断可能正在 SocketChannel 写入的线程，否则 NIO 会关闭整条连接。已经在途的通知允许完成，由客户端忽略已释放的订阅 ID；阻塞发送仍由五秒硬截止关闭连接。
 */
final class TurnStreamSubscription implements AutoCloseable {
    private final TurnStreamRpcContracts.Subscribe request;
    private final TurnStreamService streams;
    private final RpcConnection connection;
    private final CanonicalJson json;
    private final ScheduledExecutorService deadlines;
    private final Object signal = new Object();
    private final AutoCloseable listener;
    private final Thread worker;
    private boolean dirty = true;
    private boolean closed;
    private String cursor;

    TurnStreamSubscription(
            TurnStreamRpcContracts.Subscribe request,
            TurnStreamService streams,
            RpcConnection connection,
            CanonicalJson json,
            ScheduledExecutorService deadlines) {
        this.request = request;
        this.streams = streams;
        this.connection = connection;
        this.json = json;
        this.deadlines = deadlines;
        cursor = request.afterCursor();
        listener = streams.listen(request.turnId(), this::wake);
        worker = Thread.ofVirtual()
                .name("turn-stream-" + request.subscriptionId())
                .unstarted(this::run);
    }

    void start() {
        worker.start();
    }

    private void wake() {
        synchronized (signal) {
            dirty = true;
            signal.notifyAll();
        }
    }

    private void run() {
        try {
            while (awaitWork()) {
                drain();
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException | IOException failure) {
            closeConnection();
        } finally {
            close();
        }
    }

    private boolean awaitWork() throws InterruptedException {
        synchronized (signal) {
            if (!dirty && !closed) {
                signal.wait(Duration.ofSeconds(5).toMillis());
            }
            if (closed) {
                return false;
            }
            dirty = false;
        }
        // 固定合并窗口，不随连续 token 重置；这里仅延迟读取持久日志。
        Thread.sleep(50);
        return !isClosed();
    }

    private void drain() throws IOException {
        TurnStreamRpcContracts.Page page;
        do {
            page = streams.list(new TurnStreamRpcContracts.ListRequest(request.turnId(), cursor, 128));
            if (!page.events().isEmpty()) {
                send(new TurnStreamRpcContracts.Notification(
                        request.subscriptionId(), page.events(), Optional.empty()));
                cursor = page.nextCursor();
            }
        } while (page.hasMore() && !isClosed());
        // 捕获水位后再次排空，水位绝不能越过尚未发送的事件。
        var watermark = streams.watermark(request.turnId());
        if (!watermark.lastCursor().equals(cursor)) {
            wake();
            return;
        }
        send(new TurnStreamRpcContracts.Notification(request.subscriptionId(), List.of(), Optional.of(watermark)));
    }

    private void send(TurnStreamRpcContracts.Notification notification) throws IOException {
        if (isClosed()) {
            return;
        }
        var deadline = deadlines.schedule(this::closeConnection, 5, TimeUnit.SECONDS);
        try {
            connection.send(new JsonRpcNotification(TurnStreamRpcContracts.EVENT, json.encode(notification)));
        } finally {
            deadline.cancel(false);
        }
    }

    private boolean isClosed() {
        synchronized (signal) {
            return closed;
        }
    }

    private void closeConnection() {
        try {
            connection.close();
        } catch (IOException failure) {
            close();
        }
    }

    @Override
    public void close() {
        synchronized (signal) {
            if (closed) {
                return;
            }
            closed = true;
            signal.notifyAll();
        }
        try {
            listener.close();
        } catch (Exception failure) {
            throw new IllegalStateException("释放聊天流监听失败", failure);
        }
    }
}
