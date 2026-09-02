package com.javaclaw.runtime;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 进程级活动子 Thread 门禁，固定最多 8 个。
 *
 * <p>Lease 必须在子 Turn 进入终态后关闭；重复 close 不会重复扣减。
 */
public final class ChildThreadLimiter {
    /** 平台固定活动子 Thread 上限。 */
    public static final int MAX_ACTIVE_CHILDREN = 8;

    private final AtomicInteger active = new AtomicInteger();

    /** 创建空门禁。 */
    public ChildThreadLimiter() {}

    /**
     * 尝试占用容量。
     *
     * @return 必须关闭的 lease
     * @throws BudgetExceededException 当前已有 8 个活动子 Thread
     */
    public Lease acquire() {
        while (true) {
            int current = active.get();
            if (current >= MAX_ACTIVE_CHILDREN) {
                throw new BudgetExceededException("active child thread limit exceeded");
            }
            if (active.compareAndSet(current, current + 1)) {
                return new Lease(active);
            }
        }
    }

    /**
     * 返回活动数量。
     *
     * @return 0 到 8
     */
    public int activeCount() {
        return active.get();
    }

    /** 容量 lease。 */
    public static final class Lease implements AutoCloseable {
        private final AtomicInteger active;
        private final AtomicBoolean closed = new AtomicBoolean();

        private Lease(AtomicInteger active) {
            this.active = active;
        }

        /** 释放一次容量；重复调用无副作用。 */
        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                active.decrementAndGet();
            }
        }
    }
}
