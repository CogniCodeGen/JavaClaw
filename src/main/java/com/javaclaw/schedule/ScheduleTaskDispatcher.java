package com.javaclaw.schedule;

import com.javaclaw.platform.execution.TaskHandle;
import com.javaclaw.platform.execution.TaskScope;
import com.javaclaw.platform.execution.TaskSpec;

import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.FutureTask;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 定时任务正文的串行派发边界。
 *
 * <p>生产实现把正文提交到工作区专属的 {@link TaskScope}，因此使用全局 I/O 虚拟线程、
 * 继承统一并发配额，并在工作区关闭时收到取消。终止回调只在承载线程真正退出（或排队任务
 * 确认未启动）后调用，避免取消完成信号提前释放同任务去重位。</p>
 */
interface ScheduleTaskDispatcher extends AutoCloseable {

    Cancellation submit(String name, Runnable body, Runnable terminated);

    @Override
    void close();

    @FunctionalInterface
    interface Cancellation {
        void cancel();
    }

    static ScheduleTaskDispatcher managed(TaskScope scope) {
        Objects.requireNonNull(scope, "scope");
        return new ScheduleTaskDispatcher() {
            @Override
            public Cancellation submit(String name, Runnable body, Runnable terminated) {
                AtomicInteger lifecycle = new AtomicInteger(0); // 0 queued, 1 running, 2 terminated
                TaskHandle<Void> handle = scope.submit(TaskSpec.io(name), context -> {
                    if (!lifecycle.compareAndSet(0, 1)) {
                        return null;
                    }
                    try {
                        context.cancellation().throwIfCancellationRequested();
                        body.run();
                        return null;
                    } finally {
                        if (lifecycle.compareAndSet(1, 2)) {
                            terminated.run();
                        }
                    }
                });
                handle.completion().whenComplete((ignored, failure) -> {
                    if (lifecycle.compareAndSet(0, 2)) {
                        terminated.run();
                    }
                });
                return handle::cancel;
            }

            @Override
            public void close() {
                scope.close();
            }
        };
    }

    /** 仅供包内单元测试保留可控的传统 ExecutorService 注入。 */
    static ScheduleTaskDispatcher testing(ExecutorService executor) {
        Objects.requireNonNull(executor, "executor");
        return new ScheduleTaskDispatcher() {
            @Override
            public Cancellation submit(String name, Runnable body, Runnable terminated) {
                FutureTask<Void> future = new FutureTask<>(() -> {
                    body.run();
                    return null;
                }) {
                    @Override
                    protected void done() {
                        terminated.run();
                    }
                };
                executor.execute(future);
                return () -> future.cancel(true);
            }

            @Override
            public void close() {
                executor.shutdownNow();
            }
        };
    }
}
