package com.javaclaw.plugin.api.exec;

/**
 * 有返回值的插件任务（同步调用单元）。在宿主托管的虚拟线程上执行，调用方阻塞等待结果。
 *
 * @param <T> 返回值类型
 * @author JavaClaw
 */
@FunctionalInterface
public interface PluginCallable<T> {

    /**
     * 任务体，返回结果。允许抛出受检异常，宿主会包装为 {@code PluginExecException} 抛回调用方。
     */
    T call() throws Exception;
}
