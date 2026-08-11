package com.javaclaw.platform.execution;

import java.util.concurrent.CompletableFuture;

/**
 * 托管任务句柄。
 *
 * <p>状态只会向终态前进。{@link #cancel()} 同时翻转协作信号并中断承载线程；
 * {@link #close()} 等价于取消，且可重复调用。</p>
 */
public interface TaskHandle<T> extends AutoCloseable {

    String id();

    TaskSpec spec();

    TaskState state();

    CompletableFuture<T> completion();

    boolean cancel();

    @Override
    default void close() {
        cancel();
    }
}
