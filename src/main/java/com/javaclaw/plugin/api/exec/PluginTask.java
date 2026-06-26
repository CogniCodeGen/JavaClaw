package com.javaclaw.plugin.api.exec;

/**
 * 无返回值的插件任务（异步提交单元）。在宿主托管的虚拟线程上执行。
 *
 * @author JavaClaw
 */
@FunctionalInterface
public interface PluginTask {

    /**
     * 任务体。允许抛出受检异常，宿主会捕获并记录（不会拖垮宿主或其他插件）。
     */
    void run() throws Exception;
}
