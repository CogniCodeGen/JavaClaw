package com.javaclaw.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 节流持久化器：将高频触发的保存操作合并为固定间隔的最多一次执行。
 *
 * <p>使用场景：TaskExecutor 进度更新、ChatHistoryManager 大段 diff 等每秒多次触发的保存。
 * 关键事件（如任务完成、用户主动保存）仍应调用 {@link #flush()} 立即落盘。</p>
 */
public final class DebouncedPersister {

    private static final Logger log = LoggerFactory.getLogger(DebouncedPersister.class);

    private final Runnable task;
    private final long delayMillis;
    private final ScheduledExecutorService scheduler;
    private boolean pending;
    /** 识别已取消/已被 flush 取代的旧调度回调，避免旧回调清掉新任务。 */
    private long generation;
    /** 保证调度执行与 flush 永远不会并发进入实际持久化动作。 */
    private final ReentrantLock executionLock = new ReentrantLock();
    private volatile ScheduledFuture<?> scheduled;
    private volatile boolean shutdown;

    /**
     * @param name  线程名前缀，便于排查
     * @param delay 合并窗口
     * @param task  实际执行保存的动作（内部需自行处理异常）
     */
    public DebouncedPersister(String name, Duration delay, Runnable task) {
        this.task = task;
        this.delayMillis = delay.toMillis();
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "debounced-" + name);
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * 请求一次保存；若窗口内已有待执行任务则合并。
     */
    public synchronized void request() {
        if (shutdown || pending) return;
        pending = true;
        long ticket = ++generation;
        try {
            scheduled = scheduler.schedule(() -> runOnce(ticket), delayMillis, TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.RejectedExecutionException e) {
            // shutdown 与 request 竞态：关闭后不再接受新的持久化请求。
            pending = false;
        }
    }

    /**
     * 立即执行一次保存并清空待执行状态（同步阻塞调用线程）。
     */
    public synchronized void flush() {
        if (scheduled != null) {
            scheduled.cancel(false);
            scheduled = null;
        }
        pending = false;
        generation++;
        runTask("flush 执行保存任务失败");
    }

    private void runTask(String failureMessage) {
        executionLock.lock();
        try {
            task.run();
        } catch (Exception e) {
            log.warn(failureMessage, e);
        } finally {
            executionLock.unlock();
        }
    }

    private void runOnce(long ticket) {
        synchronized (this) {
            if (!pending || ticket != generation) return;
            pending = false;
            scheduled = null;
        }
        runTask("合并保存任务执行异常");
    }

    /**
     * 关闭调度器，退出前应先调用 {@link #flush()}。
     */
    public synchronized void shutdown() {
        shutdown = true;
        if (scheduled != null) {
            scheduled.cancel(false);
            scheduled = null;
        }
        pending = false;
        generation++;
        scheduler.shutdownNow();
    }
}
