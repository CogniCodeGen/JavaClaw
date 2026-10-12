package com.javaclaw.platform.execution;

/** 可抛受检异常、可协作取消的托管任务正文。 */
@FunctionalInterface
public interface ManagedTask<T> {
    T run(TaskContext context) throws Exception;
}
