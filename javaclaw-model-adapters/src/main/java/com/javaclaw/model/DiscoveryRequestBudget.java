package com.javaclaw.model;

import java.io.IOException;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;

/** 模型目录发现共享的请求次数与总时限预算。 */
final class DiscoveryRequestBudget {
    private final long startedNanos;
    private final long timeoutNanos;
    private final int maximumRequests;
    private final LongSupplier nanoTime;
    private final AtomicInteger requests = new AtomicInteger();

    private DiscoveryRequestBudget(Duration timeout, int maximumRequests, LongSupplier nanoTime) {
        Duration checkedTimeout = Objects.requireNonNull(timeout, "timeout");
        if (checkedTimeout.isZero() || checkedTimeout.isNegative()) {
            throw new IllegalArgumentException("timeout must be positive");
        }
        if (maximumRequests < 1) {
            throw new IllegalArgumentException("maximumRequests must be positive");
        }
        this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
        this.startedNanos = nanoTime.getAsLong();
        this.timeoutNanos = checkedTimeout.toNanos();
        this.maximumRequests = maximumRequests;
    }

    /** @return 使用系统单调时钟创建的共享预算 */
    static DiscoveryRequestBudget start(Duration timeout, int maximumRequests) {
        return new DiscoveryRequestBudget(timeout, maximumRequests, System::nanoTime);
    }

    /** @return 使用指定单调时钟创建的预算，仅供确定性边界测试 */
    static DiscoveryRequestBudget start(Duration timeout, int maximumRequests, LongSupplier nanoTime) {
        return new DiscoveryRequestBudget(timeout, maximumRequests, nanoTime);
    }

    /**
     * 消耗一次请求额度并返回本次请求可使用的剩余毫秒数。
     *
     * @return 至少 1 毫秒的剩余时限
     * @throws IOException 请求数或总时限已经耗尽
     */
    int acquireTimeoutMillis() throws IOException {
        if (requests.incrementAndGet() > maximumRequests) {
            throw new IOException("Provider model discovery request limit exceeded");
        }
        long elapsed = nanoTime.getAsLong() - startedNanos;
        long remaining = timeoutNanos - elapsed;
        if (remaining <= 0) {
            throw new IOException("Provider model discovery timeout exceeded");
        }
        long remainingMillis = Math.max(1L, TimeUnit.NANOSECONDS.toMillis(remaining));
        return Math.toIntExact(Math.min(Integer.MAX_VALUE, remainingMillis));
    }
}
