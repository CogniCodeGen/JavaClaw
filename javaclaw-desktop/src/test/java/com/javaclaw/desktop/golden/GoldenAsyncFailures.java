package com.javaclaw.desktop.golden;

import java.util.concurrent.ConcurrentLinkedQueue;

import com.javaclaw.desktop.FxTestSupport;

/**
 * 把 Golden 渲染期间的后台与 JavaFX 未捕获异常纳入测试结果。
 *
 * <p>仅用于串行测试；关闭前经过 FX 队列屏障，并恢复原处理器，避免污染后续测试。JavaFX 属性监听器会直接调用当前线程的异常处理器， 因此需要同时登记 FX 线程处理器与默认后台线程处理器。
 */
final class GoldenAsyncFailures implements AutoCloseable {
    private final ConcurrentLinkedQueue<Throwable> failures = new ConcurrentLinkedQueue<>();
    private final Thread.UncaughtExceptionHandler previousDefault;
    private final Thread.UncaughtExceptionHandler previousFx;

    GoldenAsyncFailures() {
        previousDefault = Thread.getDefaultUncaughtExceptionHandler();
        previousFx = FxTestSupport.call(() -> {
            Thread thread = Thread.currentThread();
            Thread.UncaughtExceptionHandler previous = thread.getUncaughtExceptionHandler();
            thread.setUncaughtExceptionHandler(this::record);
            return previous;
        });
        Thread.setDefaultUncaughtExceptionHandler(this::record);
    }

    private void record(Thread thread, Throwable failure) {
        failures.add(failure);
    }

    /** 排空已入队的 FX 回调并恢复异常处理器；出现任何未捕获异常时使当前测试失败。 */
    @Override
    public void close() {
        try {
            FxTestSupport.run(() -> Thread.currentThread().setUncaughtExceptionHandler(previousFx));
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(previousDefault);
        }
        Throwable first = failures.poll();
        if (first == null) {
            return;
        }
        AssertionError failure = new AssertionError("Golden 渲染存在未捕获的异步异常", first);
        failures.forEach(failure::addSuppressed);
        throw failure;
    }
}
