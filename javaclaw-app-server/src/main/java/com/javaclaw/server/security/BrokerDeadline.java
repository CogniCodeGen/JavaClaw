package com.javaclaw.server.security;

import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.Objects;

import com.javaclaw.api.CancellationToken;

/** 单次 Broker 调用跨 DNS、连接、重定向与读取共享的单调时限。 */
final class BrokerDeadline {
    private final long deadlineNanos;

    private BrokerDeadline(Duration timeout) {
        long now = System.nanoTime();
        long timeoutNanos = timeout.toNanos();
        deadlineNanos = timeoutNanos > Long.MAX_VALUE - now ? Long.MAX_VALUE : now + timeoutNanos;
    }

    static BrokerDeadline start(Duration timeout) {
        Duration checked = Objects.requireNonNull(timeout, "timeout");
        if (checked.isZero() || checked.isNegative()) {
            throw new IllegalArgumentException("Broker timeout must be positive");
        }
        return new BrokerDeadline(checked);
    }

    Duration remaining(CancellationToken cancellation) throws SocketTimeoutException {
        Objects.requireNonNull(cancellation, "cancellation").throwIfCancelled();
        long nanos = deadlineNanos - System.nanoTime();
        if (nanos <= 0) {
            throw new SocketTimeoutException("Network Broker deadline exceeded");
        }
        return Duration.ofNanos(nanos);
    }

    int timeoutMillis(CancellationToken cancellation, int maximumMillis) throws SocketTimeoutException {
        long remaining = Math.max(1, remaining(cancellation).toMillis());
        return Math.toIntExact(Math.min(remaining, maximumMillis));
    }
}
