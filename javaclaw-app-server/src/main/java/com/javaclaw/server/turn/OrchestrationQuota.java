package com.javaclaw.server.turn;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import com.javaclaw.api.ThreadId;
import com.javaclaw.runtime.BudgetExceededException;
import com.javaclaw.runtime.ChildThreadLimiter;

/** 同时执行进程级 8 个、单父 Thread 4 个活动子 Thread 上限。 */
final class OrchestrationQuota {
    private static final int MAX_PER_PARENT = 4;

    private final ChildThreadLimiter global = new ChildThreadLimiter();
    private final Map<ThreadId, AtomicInteger> parents = new ConcurrentHashMap<>();

    Lease acquire(ThreadId parentId) {
        ChildThreadLimiter.Lease globalLease = global.acquire();
        if (parentId == null) {
            return new Lease(globalLease, null, null);
        }
        AtomicInteger parent = parents.computeIfAbsent(parentId, ignored -> new AtomicInteger());
        int current = parent.incrementAndGet();
        if (current > MAX_PER_PARENT) {
            releaseParent(parentId, parent);
            globalLease.close();
            throw new BudgetExceededException("active child limit for parent Thread exceeded");
        }
        return new Lease(globalLease, parentId, parent);
    }

    private void releaseParent(ThreadId parentId, AtomicInteger counter) {
        if (counter.decrementAndGet() == 0) {
            parents.remove(parentId, counter);
        }
    }

    /** 活动子 Turn 配额 lease。 */
    final class Lease implements AutoCloseable {
        private final ChildThreadLimiter.Lease globalLease;
        private final ThreadId parentId;
        private final AtomicInteger parent;
        private final AtomicBoolean closed = new AtomicBoolean();

        private Lease(ChildThreadLimiter.Lease globalLease, ThreadId parentId, AtomicInteger parent) {
            this.globalLease = Objects.requireNonNull(globalLease, "globalLease");
            this.parentId = parentId;
            this.parent = parent;
        }

        /** 只释放一次父级与进程级容量。 */
        @Override
        public void close() {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            if (parentId != null) {
                releaseParent(parentId, parent);
            }
            globalLease.close();
        }
    }
}
