package com.javaclaw.memory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * 跟踪一代记忆后台任务的接受、取消与排空状态。
 *
 * <p>关闭方先停止发放租约，再等待或取消存量任务。排空回调最多登记一次，并由最后一个
 * 退出的任务线程执行；这样即使底层调用延迟响应取消，也无需额外创建清理线程。</p>
 *
 * <p>该类型线程安全。每次 {@link #startAccepting()} 开始一个新生命周期；已获取的租约必须
 * 关闭。取消是协作式的，绑定的动作最多调用一次。</p>
 */
final class MemoryTaskTracker {

    private static final Logger log = LoggerFactory.getLogger(MemoryTaskTracker.class);

    private boolean accepting;
    private final Set<WorkLease> active = new HashSet<>();
    private Runnable drainedAction;
    private boolean drainedActionRegistered;

    synchronized void startAccepting() {
        if (!active.isEmpty()) {
            throw new IllegalStateException("仍有记忆后台任务未结束");
        }
        drainedAction = null;
        drainedActionRegistered = false;
        accepting = true;
    }

    synchronized WorkLease tryAcquire() {
        if (!accepting) {
            return null;
        }
        WorkLease lease = new WorkLease(this);
        active.add(lease);
        return lease;
    }

    synchronized void stopAccepting() {
        accepting = false;
    }

    synchronized void awaitDrained() {
        boolean interrupted = false;
        while (!active.isEmpty()) {
            try {
                wait();
            } catch (InterruptedException e) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    synchronized boolean awaitDrained(long timeout, TimeUnit unit) {
        long remaining = unit.toNanos(timeout);
        long deadline = System.nanoTime() + remaining;
        while (!active.isEmpty()) {
            if (remaining <= 0) {
                return false;
            }
            try {
                TimeUnit.NANOSECONDS.timedWait(this, remaining);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
            remaining = deadline - System.nanoTime();
        }
        return true;
    }

    void cancelAll() {
        List<WorkLease> snapshot;
        synchronized (this) {
            snapshot = List.copyOf(active);
        }
        snapshot.forEach(WorkLease::cancel);
    }

    void whenDrained(Runnable action) {
        Objects.requireNonNull(action, "action");
        boolean runNow;
        synchronized (this) {
            if (drainedActionRegistered) {
                throw new IllegalStateException("排空回调只能登记一次");
            }
            drainedActionRegistered = true;
            runNow = active.isEmpty();
            if (!runNow) {
                drainedAction = action;
            }
        }
        if (runNow) {
            runSafely("记忆后台任务排空回调失败", action);
        }
    }

    private void release(WorkLease lease) {
        Runnable action = null;
        synchronized (this) {
            if (!active.remove(lease)) {
                throw new IllegalStateException("记忆后台任务租约计数失衡");
            }
            if (active.isEmpty()) {
                notifyAll();
                action = drainedAction;
                drainedAction = null;
            }
        }
        if (action != null) {
            runSafely("记忆后台任务排空回调失败", action);
        }
    }

    private static void runSafely(String message, Runnable action) {
        try {
            action.run();
        } catch (RuntimeException e) {
            log.warn("{}: {}", message, e.getMessage());
        }
    }

    static final class WorkLease implements AutoCloseable {
        private final MemoryTaskTracker owner;
        private boolean closed;
        private Runnable cancelAction;
        private boolean cancellationRequested;
        private boolean cancelActionInvoked;

        private WorkLease(MemoryTaskTracker owner) {
            this.owner = owner;
        }

        void onCancel(Runnable action) {
            Objects.requireNonNull(action, "action");
            Runnable invoke = null;
            synchronized (this) {
                if (closed) {
                    return;
                }
                if (cancelAction != null) {
                    throw new IllegalStateException("取消动作只能绑定一次");
                }
                cancelAction = action;
                if (cancellationRequested && !cancelActionInvoked) {
                    cancelActionInvoked = true;
                    invoke = action;
                }
            }
            if (invoke != null) {
                runSafely("取消记忆后台任务失败", invoke);
            }
        }

        boolean isCancellationRequested() {
            synchronized (this) {
                return cancellationRequested;
            }
        }

        void cancel() {
            Runnable invoke = null;
            synchronized (this) {
                if (closed || cancellationRequested) {
                    return;
                }
                cancellationRequested = true;
                if (cancelAction != null && !cancelActionInvoked) {
                    cancelActionInvoked = true;
                    invoke = cancelAction;
                }
            }
            if (invoke != null) {
                runSafely("取消记忆后台任务失败", invoke);
            }
        }

        @Override
        public void close() {
            synchronized (this) {
                if (closed) {
                    return;
                }
                closed = true;
                cancelAction = null;
            }
            owner.release(this);
        }
    }
}
