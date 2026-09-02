package com.javaclaw.desktop;

import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import com.javaclaw.client.ServerNotification;

/** 单个 Desktop Presenter 拥有的通知订阅目录；没有静态状态或异步队列。 */
final class DesktopNotificationRegistry implements AutoCloseable {
    private final CopyOnWriteArrayList<Entry> entries = new CopyOnWriteArrayList<>();
    private final AtomicBoolean closed = new AtomicBoolean();

    DesktopNotificationSubscription subscribe(Consumer<ServerNotification> listener) {
        if (closed.get()) {
            throw new IllegalStateException("Desktop 通知目录已经关闭");
        }
        Entry entry = new Entry(Objects.requireNonNull(listener, "listener"));
        entries.add(entry);
        if (closed.get()) {
            entry.close();
            throw new IllegalStateException("Desktop 通知目录已经关闭");
        }
        return entry;
    }

    void publish(ServerNotification notification) {
        if (!closed.get()) {
            entries.forEach(entry -> entry.deliver(notification));
        }
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            entries.forEach(Entry::close);
            entries.clear();
        }
    }

    private final class Entry implements DesktopNotificationSubscription {
        private final Consumer<ServerNotification> listener;
        private final AtomicBoolean subscriptionClosed = new AtomicBoolean();

        private Entry(Consumer<ServerNotification> listener) {
            this.listener = listener;
        }

        private void deliver(ServerNotification notification) {
            if (subscriptionClosed.get()) {
                return;
            }
            try {
                listener.accept(Objects.requireNonNull(notification, "notification"));
            } catch (RuntimeException invalidConsumer) {
                close();
            }
        }

        @Override
        public void close() {
            if (subscriptionClosed.compareAndSet(false, true)) {
                entries.remove(this);
            }
        }
    }
}
