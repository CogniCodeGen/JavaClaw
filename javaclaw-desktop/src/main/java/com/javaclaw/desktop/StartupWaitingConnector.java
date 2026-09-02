package com.javaclaw.desktop;

import java.io.IOException;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

import com.javaclaw.client.ServerNotification;
import com.javaclaw.client.sdk.JavaClawClient;

/**
 * 在一个明确的启动窗口内等待 App Server 可连接，避免并行启动 Desktop 时产生竞态。
 *
 * <p>实现说明：连接由 Presenter 的独占后台任务串行执行。线程中断会立即终止等待并保留中断标记，避免关闭 Desktop 后后台任务继续重试。
 */
final class StartupWaitingConnector implements DesktopClientConnector {
    private static final Duration DEFAULT_RETRY_INTERVAL = Duration.ofMillis(100);

    private final DesktopClientConnector delegate;
    private final long timeoutNanos;
    private final long retryIntervalNanos;
    private final LongSupplier nanoTime;
    private final RetryDelay retryDelay;

    StartupWaitingConnector(
            DesktopClientConnector delegate,
            Duration timeout,
            Duration retryInterval,
            LongSupplier nanoTime,
            RetryDelay retryDelay) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.timeoutNanos = requirePositive(timeout, "timeout").toNanos();
        this.retryIntervalNanos =
                requirePositive(retryInterval, "retryInterval").toNanos();
        this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
        this.retryDelay = Objects.requireNonNull(retryDelay, "retryDelay");
    }

    static DesktopClientConnector wrap(DesktopClientConnector delegate, Duration timeout) {
        Objects.requireNonNull(delegate, "delegate");
        Objects.requireNonNull(timeout, "timeout");
        if (timeout.isZero()) {
            return delegate;
        }
        return new StartupWaitingConnector(
                delegate, timeout, DEFAULT_RETRY_INTERVAL, System::nanoTime, TimeUnit.NANOSECONDS::sleep);
    }

    @Override
    public JavaClawClient connect(Consumer<ServerNotification> notifications) throws IOException {
        Consumer<ServerNotification> checked = Objects.requireNonNull(notifications, "notifications");
        long deadline = nanoTime.getAsLong() + timeoutNanos;
        while (true) {
            try {
                return delegate.connect(checked);
            } catch (IOException unavailable) {
                long remaining = deadline - nanoTime.getAsLong();
                if (remaining <= 0) {
                    throw new IOException("无法连接 App Server，请确认服务端已经启动", unavailable);
                }
                awaitRetry(Math.min(retryIntervalNanos, remaining), unavailable);
            }
        }
    }

    private void awaitRetry(long delayNanos, IOException unavailable) throws IOException {
        try {
            retryDelay.await(delayNanos);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("等待 App Server 启动时被取消", unavailable);
        }
    }

    private static Duration requirePositive(Duration value, String name) {
        Duration duration = Objects.requireNonNull(value, name);
        if (duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException(name + " 必须为正数");
        }
        return duration;
    }

    /** 可替换的等待边界，使重试窗口能够在测试中使用单调虚拟时间。 */
    @FunctionalInterface
    interface RetryDelay {
        /**
         * 等待下一次连接。
         *
         * @param delayNanos 等待纳秒数，正数
         * @throws InterruptedException 当前任务被取消
         */
        void await(long delayNanos) throws InterruptedException;
    }
}
