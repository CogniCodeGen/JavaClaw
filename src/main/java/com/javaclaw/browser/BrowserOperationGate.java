package com.javaclaw.browser;

import java.util.concurrent.locks.ReentrantLock;

/** Serializes all operations that share one Playwright browser context. */
final class BrowserOperationGate {

    private final ReentrantLock lock = new ReentrantLock(true);
    private boolean closed;

    void enter() {
        try {
            lock.lockInterruptibly();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("浏览器操作在等待执行时被取消", interrupted);
        }
        if (closed) {
            lock.unlock();
            throw new IllegalStateException("会话浏览器已关闭，不能继续操作");
        }
    }

    /** Optional read-only admission must not queue the primary carrier behind a browser action. */
    boolean tryEnter() {
        if (!lock.tryLock()) return false;
        if (!closed) return true;
        lock.unlock();
        return false;
    }

    /** 关闭清理不可被线程中断跳过；先阻止后续操作，再在同一把锁内释放引用。 */
    void close(Runnable cleanup) {
        lock.lock();
        try {
            if (closed) return;
            closed = true;
            cleanup.run();
        } finally {
            lock.unlock();
        }
    }

    void exit() {
        lock.unlock();
    }
}
