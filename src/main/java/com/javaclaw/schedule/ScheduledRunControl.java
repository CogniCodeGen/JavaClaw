package com.javaclaw.schedule;

import com.javaclaw.api.conversation.CancellationReason;
import com.javaclaw.browser.PlaywrightBrowserManager;
import reactor.core.Disposable;

import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 单次定时执行的控制句柄。取消标志先于订阅/浏览器建立也能生效，覆盖排队、装配和流式执行阶段。
 */
public final class ScheduledRunControl {
    private final String runId = UUID.randomUUID().toString();
    private final String taskId;
    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private final AtomicBoolean started = new AtomicBoolean(false);
    private final AtomicReference<CancellationReason> reason = new AtomicReference<>();
    private final AtomicReference<Disposable> subscription = new AtomicReference<>();
    private final AtomicReference<PlaywrightBrowserManager> browser = new AtomicReference<>();
    private final AtomicReference<Future<?>> future = new AtomicReference<>();
    private final AtomicReference<Thread> worker = new AtomicReference<>();

    public ScheduledRunControl(String taskId) {
        this.taskId = Objects.requireNonNull(taskId, "taskId");
    }

    public String runId() { return runId; }
    public String taskId() { return taskId; }
    public boolean isCancelled() { return cancelled.get(); }
    public boolean markStarted() { return started.compareAndSet(false, true); }
    public boolean hasStarted() { return started.get(); }
    public CancellationReason cancellationReason() {
        CancellationReason value = reason.get();
        return value == null ? CancellationReason.SCHEDULE_DISABLED : value;
    }

    public boolean cancel(CancellationReason cancellationReason) {
        Objects.requireNonNull(cancellationReason, "cancellationReason");
        reason.compareAndSet(null, cancellationReason);
        boolean accepted = cancelled.compareAndSet(false, true);
        Disposable currentSubscription = subscription.get();
        if (currentSubscription != null) currentSubscription.dispose();
        PlaywrightBrowserManager currentBrowser = browser.get();
        if (currentBrowser != null) safeClose(currentBrowser);
        Future<?> currentFuture = future.get();
        // 排队任务可直接从执行器取消；运行中 FutureTask.cancel()
        // 会立即触发 done()，导致活跃去重位提前释放，旧执行未退出时
        // 新触发就能穿透。运行中只 dispose/关浏览器/中断 worker，
        // 由 callable 真正退出后的 done() 释放去重位。
        if (currentFuture != null && !hasStarted()) currentFuture.cancel(true);
        Thread currentWorker = worker.get();
        if (currentWorker != null && currentWorker != Thread.currentThread()) currentWorker.interrupt();
        return accepted;
    }

    public void attachFuture(Future<?> value) {
        future.set(value);
        if (isCancelled() && value != null) value.cancel(true);
    }

    public void attachWorker(Thread value) {
        worker.set(value);
        if (isCancelled() && value != null && value != Thread.currentThread()) value.interrupt();
    }

    public void attachSubscription(Disposable value) {
        subscription.set(value);
        if (isCancelled() && value != null) value.dispose();
    }

    public void clearSubscription(Disposable expected) {
        subscription.compareAndSet(expected, null);
    }

    public void attachBrowser(PlaywrightBrowserManager value) {
        browser.set(value);
        if (isCancelled() && value != null) safeClose(value);
    }

    public void clearBrowser(PlaywrightBrowserManager expected) {
        browser.compareAndSet(expected, null);
    }

    public void detachWorker() {
        worker.set(null);
    }

    private static void safeClose(PlaywrightBrowserManager value) {
        try {
            value.shutdown();
        } catch (RuntimeException ignored) {
            // 执行体 finally 会再次幂等关闭并记录完整错误。
        }
    }
}
