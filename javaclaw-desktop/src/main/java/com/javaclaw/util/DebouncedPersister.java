package com.javaclaw.util;

import com.javaclaw.platform.execution.ManagedTaskExecutor;
import com.javaclaw.platform.execution.TaskHandle;
import com.javaclaw.platform.execution.TaskSpec;
import com.javaclaw.platform.execution.TaskSubmitter;
import com.javaclaw.platform.execution.TriggerHandle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 节流持久化器：将高频触发的保存操作合并为固定间隔的最多一次执行。
 *
 * <p>延迟触发由根 {@link ManagedTaskExecutor} 调度，持久化正文提交到调用方提供的
 * {@link TaskSubmitter} 生命周期作用域。关键事件仍应调用 {@link #flush()} 同步落盘。
 * 实例线程安全；关闭后请求被忽略，关闭本身不会隐式保存。</p>
 */
public final class DebouncedPersister {

    private static final Logger log = LoggerFactory.getLogger(DebouncedPersister.class);

    private final String taskName;
    private final Runnable task;
    private final Duration delay;
    private final ManagedTaskExecutor scheduler;
    private final TaskSubmitter tasks;
    private boolean pending;
    /** 识别已取消/已被 flush 取代的旧调度回调，避免旧回调清掉新任务。 */
    private long generation;
    /** 保证调度执行与 flush 永远不会并发进入实际持久化动作。 */
    private final ReentrantLock executionLock = new ReentrantLock();
    private volatile TriggerHandle scheduled;
    private volatile TaskHandle<Void> submitted;
    private volatile boolean shutdown;

    /**
     * @param name      可诊断的任务名称
     * @param delay     合并窗口，不能为负数
     * @param scheduler 根 Context 的轻量调度器
     * @param tasks     持久化正文所属的任务作用域
     * @param task      实际保存动作；异常会被记录，不向调度线程传播
     */
    public DebouncedPersister(
            String name,
            Duration delay,
            ManagedTaskExecutor scheduler,
            TaskSubmitter tasks,
            Runnable task) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("持久化任务名称不能为空");
        }
        if (delay == null || delay.isNegative()) {
            throw new IllegalArgumentException("持久化合并窗口不能为 null 或负数");
        }
        this.taskName = name;
        this.delay = delay;
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.tasks = Objects.requireNonNull(tasks, "tasks");
        this.task = Objects.requireNonNull(task, "task");
    }

    /**
     * 请求一次保存；若窗口内已有待执行任务则合并。
     */
    public synchronized void request() {
        if (shutdown || pending) return;
        pending = true;
        long ticket = ++generation;
        try {
            scheduled = scheduler.scheduleTrigger(delay, () -> dispatch(ticket));
        } catch (RejectedExecutionException e) {
            // shutdown 与 request 竞态：关闭后不再接受新的持久化请求。
            pending = false;
        }
    }

    /**
     * 立即执行一次保存并清空待执行状态（同步阻塞调用线程）。
     */
    public synchronized void flush() {
        if (scheduled != null) {
            scheduled.cancel();
            scheduled = null;
        }
        TaskHandle<Void> current = submitted;
        if (current != null) {
            current.cancel();
            submitted = null;
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

    private void dispatch(long ticket) {
        synchronized (this) {
            if (shutdown || !pending || ticket != generation) return;
            pending = false;
            scheduled = null;
            try {
                submitted = tasks.submit(TaskSpec.io(taskName + "-persist"), context -> {
                    context.cancellation().throwIfCancellationRequested();
                    runTask("合并保存任务执行异常");
                    return null;
                });
            } catch (RejectedExecutionException ignored) {
                // 工作区正在关闭；关闭协议负责在资源释放前显式 flush。
            }
        }
    }

    /**
     * 关闭并取消尚未开始的触发与任务。退出前应先调用 {@link #flush()}。
     */
    public synchronized void shutdown() {
        shutdown = true;
        if (scheduled != null) {
            scheduled.cancel();
            scheduled = null;
        }
        if (submitted != null) {
            submitted.cancel();
            submitted = null;
        }
        pending = false;
        generation++;
    }
}
