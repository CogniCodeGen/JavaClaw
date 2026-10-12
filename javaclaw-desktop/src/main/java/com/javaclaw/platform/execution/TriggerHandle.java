package com.javaclaw.platform.execution;

/**
 * 轻量调度触发句柄。
 *
 * <p>触发器只负责在指定时刻提交真正的任务，不得在调度线程中执行阻塞或耗时工作。
 * 句柄线程安全；关闭等价于取消后续触发，且可重复调用。</p>
 */
public interface TriggerHandle extends AutoCloseable {

    boolean cancel();

    boolean isCancelled();

    @Override
    default void close() {
        cancel();
    }
}
