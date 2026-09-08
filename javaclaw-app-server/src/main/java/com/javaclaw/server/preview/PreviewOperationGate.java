package com.javaclaw.server.preview;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * 预览服务关闭屏障，覆盖预留缓存之前的来源读取以及读取期间的实时权限核验。
 *
 * <p>关闭先封住入口并取消 Worker，再等待已入场操作退出；不以中断 JDBC 的方式释放所有权。 业务操作可嵌套进入，通知回调只能入队，不得在操作内同步关闭服务。
 */
final class PreviewOperationGate {
    private final AtomicBoolean stopping = new AtomicBoolean();
    private final ReentrantReadWriteLock operations = new ReentrantReadWriteLock(true);

    Lease enter() {
        Lock lock = operations.readLock();
        lock.lock();
        if (stopping.get()) {
            lock.unlock();
            throw new IllegalStateException("PREVIEW_CLOSED: 服务已关闭");
        }
        return new Lease(lock);
    }

    void stopAccepting() {
        stopping.set(true);
    }

    void awaitIdle() {
        operations.writeLock().lock();
        try {
            // 获得写锁意味着来源读取、权限核验和缓存创建均已归还，之后 owner 才能释放数据库。
        } finally {
            operations.writeLock().unlock();
        }
    }

    /**
     * 同一线程拥有的操作范围，退出只归还读锁，不撤销其他操作。
     *
     * @param lock 已由当前线程取得的非空读锁
     */
    record Lease(Lock lock) implements AutoCloseable {
        @Override
        public void close() {
            lock.unlock();
        }
    }
}
