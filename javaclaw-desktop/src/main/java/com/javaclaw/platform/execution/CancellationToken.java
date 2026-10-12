package com.javaclaw.platform.execution;

import java.util.concurrent.CancellationException;

/** 只读协作取消信号；任务还必须正确响应线程中断。 */
@FunctionalInterface
public interface CancellationToken {

    boolean isCancellationRequested();

    default void throwIfCancellationRequested() {
        if (isCancellationRequested() || Thread.currentThread().isInterrupted()) {
            throw new CancellationException("任务已取消");
        }
    }
}
