package com.javaclaw.browser;

import java.util.concurrent.locks.ReentrantLock;

/** Serializes all operations that share one Playwright browser context. */
final class BrowserOperationGate {

    private final ReentrantLock lock = new ReentrantLock(true);

    void enter() {
        try {
            lock.lockInterruptibly();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("浏览器操作在等待执行时被取消", interrupted);
        }
    }

    void exit() {
        lock.unlock();
    }
}
