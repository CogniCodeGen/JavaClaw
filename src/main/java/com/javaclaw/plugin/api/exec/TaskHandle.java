package com.javaclaw.plugin.api.exec;

import java.util.concurrent.CompletionStage;

/**
 * Plugin API 3.0 的统一任务句柄。
 *
 * <p>句柄线程安全。状态只向终态推进；取消会停止后续周期触发、翻转协作信号并中断当前
 * 承载虚拟线程。{@link #close()} 等价于取消，且可重复调用。</p>
 *
 * @param <T> 完成值类型
 */
public interface TaskHandle<T> extends AutoCloseable {

    String id();

    TaskState state();

    CompletionStage<T> completion();

    boolean cancel();

    @Override
    default void close() {
        cancel();
    }
}
