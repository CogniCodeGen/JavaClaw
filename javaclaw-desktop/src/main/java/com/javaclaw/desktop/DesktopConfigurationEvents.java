package com.javaclaw.desktop;

import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * 单个 Desktop Presenter 拥有的配置失效订阅，不持有全局静态状态。
 *
 * <p>成功的 SDK 写操作在 UI 调度器上发布；读取和失败写操作不能发布事件。事件只使快照失效，
 * 接收方必须重新读取权威配置，并自行保护草稿、合并在途失效及隔离旧作用域响应。
 */
public final class DesktopConfigurationEvents implements AutoCloseable {
    private final CopyOnWriteArrayList<Entry> entries = new CopyOnWriteArrayList<>();
    private final AtomicBoolean closed = new AtomicBoolean();

    /** 创建一个与 Desktop 会话同生命周期的事件源。 */
    public DesktopConfigurationEvents() {}

    /**
     * 注册快速、非阻塞的监听者；异常监听者会被取消，不影响其他监听者和已成功的写操作。
     *
     * @param listener 失效事件监听者，不能为空
     * @return 幂等取消句柄
     */
    public DesktopNotificationSubscription subscribe(Consumer<DesktopConfigurationChange> listener) {
        if (closed.get()) {
            throw new IllegalStateException("Desktop 配置事件源已经关闭");
        }
        Entry entry = new Entry(Objects.requireNonNull(listener, "listener"));
        entries.add(entry);
        if (closed.get()) {
            entry.close();
            throw new IllegalStateException("Desktop 配置事件源已经关闭");
        }
        return entry;
    }

    /**
     * 同步投递成功写入的失效范围；关闭后不再投递。
     *
     * @param change 不包含业务正文的配置失效事件，不能为空
     */
    public void publish(DesktopConfigurationChange change) {
        Objects.requireNonNull(change, "change");
        if (!closed.get()) {
            entries.forEach(entry -> entry.deliver(change));
        }
    }

    /** 幂等取消全部监听，不关闭共享 SDK 会话。 */
    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            entries.forEach(Entry::close);
            entries.clear();
        }
    }

    private final class Entry implements DesktopNotificationSubscription {
        private final Consumer<DesktopConfigurationChange> listener;
        private final AtomicBoolean subscriptionClosed = new AtomicBoolean();

        private Entry(Consumer<DesktopConfigurationChange> listener) {
            this.listener = listener;
        }

        private void deliver(DesktopConfigurationChange change) {
            if (subscriptionClosed.get()) {
                return;
            }
            try {
                listener.accept(change);
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
