package com.javaclaw.platform.execution;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 共享全局执行资源的生命周期与配额边界。
 *
 * <p>工作区和插件各持有独立作用域。关闭后拒绝提交、取消全部登记任务；关闭可重复调用。
 * 底层执行器归根 Context 所有，关闭作用域不会影响其他工作区或插件。</p>
 */
public final class TaskScope implements TaskSubmitter, AutoCloseable {

    private final String name;
    private final ManagedTaskExecutor executor;
    private final Semaphore quota;
    private final Set<TaskHandle<?>> handles = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean accepting = new AtomicBoolean(true);

    TaskScope(String name, ManagedTaskExecutor executor, int maxConcurrentTasks) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("作用域名称不能为空");
        }
        if (maxConcurrentTasks < 1) {
            throw new IllegalArgumentException("作用域并发上限必须大于 0");
        }
        this.name = name;
        this.executor = executor;
        this.quota = new Semaphore(maxConcurrentTasks, true);
    }

    @Override
    public <T> TaskHandle<T> submit(TaskSpec spec, ManagedTask<T> task) {
        if (!accepting.get()) {
            throw new RejectedExecutionException("任务作用域已关闭: " + name);
        }
        TaskHandle<T> handle = executor.submit(spec, context -> {
            quota.acquire();
            try {
                context.cancellation().throwIfCancellationRequested();
                return task.run(context);
            } finally {
                quota.release();
            }
        });
        handles.add(handle);
        executor.whenTerminated(handle, () -> handles.remove(handle));
        if (!accepting.get()) {
            handle.cancel();
        }
        return handle;
    }

    public int activeTaskCount() {
        return handles.size();
    }

    @Override
    public void close() {
        if (!accepting.compareAndSet(true, false)) {
            return;
        }
        List<TaskHandle<?>> snapshot = List.copyOf(handles);
        for (TaskHandle<?> handle : snapshot) {
            handle.cancel();
        }
        executor.awaitTasks(snapshot, Duration.ofSeconds(5));
        handles.clear();
    }
}
