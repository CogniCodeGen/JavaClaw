package com.javaclaw.plugin.api.exec;

/**
 * 后台监听循环体 —— 提交给 {@code PluginExecutor.background(...)}，在宿主托管的专属虚拟线程上长期运行。
 *
 * <p>典型用法是一个 {@code while (!c.isCancelled()) { ... }} 循环（如阻塞接收飞书消息）。
 * 得益于 JDK 25 虚拟线程，阻塞 I/O 不占用平台载体线程，一个插件挂多个监听循环成本极低。</p>
 *
 * @author JavaClaw
 */
@FunctionalInterface
public interface LoopBody {

    /**
     * 循环体。
     *
     * @param c 取消信号，循环应周期性检查 {@link Cancellation#isCancelled()} 并在置位后退出
     * @throws Exception 抛出将被宿主捕获记录，并结束该后台服务（不波及其他插件）
     */
    void run(Cancellation c) throws Exception;
}
