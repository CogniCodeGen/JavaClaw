package com.javaclaw.plugin.api.exec;

import java.time.Duration;

/**
 * 插件执行面 —— 插件获取"并发能力"的唯一入口，背后是宿主为该插件独占的虚拟线程执行器。
 *
 * <p><b>插件不创建任何真实线程</b>（连 {@code Thread.ofVirtual()} 也无须、不应使用）：所有同步/异步/
 * 后台执行都向本接口申请，跑在宿主命名（{@code plugin-{id}-vt-N}）、记账、限额、可中断的虚拟线程上。
 * 返回的均为控制句柄（{@link ManagedTask}/{@link ServiceHandle}），插件碰不到底层并发对象。</p>
 *
 * <p>JDK 25 虚拟线程无 pinning，阻塞 I/O 不挤占平台载体线程，故后台监听循环成本极低。</p>
 *
 * @author JavaClaw
 */
public interface PluginExecutor {

    /**
     * 异步提交一个任务，立即返回句柄。任务在托管虚拟线程上执行。
     *
     * @param task 任务体
     * @return 任务句柄（可取消、可查状态）
     */
    ManagedTask submit(PluginTask task);

    /**
     * 同步执行一个任务并阻塞返回其结果。任务仍跑在受控虚拟线程上（受命名/超时/取消约束），
     * 而非裸跑在调用方线程。
     *
     * @param task 有返回值的任务体
     * @param <T>  返回类型
     * @return 任务结果
     * @throws Exception 任务抛出的异常（受检异常被包装）将原样/包装后抛回
     */
    <T> T call(PluginCallable<T> task) throws Exception;

    /**
     * 注册一个长活后台监听循环（如阻塞接收外部消息），在专属虚拟线程上运行直至取消。
     *
     * @param name 服务名（用于日志与线程命名后缀，中文友好）
     * @param loop 循环体，应周期性检查取消信号
     * @return 后台服务句柄
     */
    ServiceHandle background(String name, LoopBody loop);

    /**
     * 延时执行一次。
     *
     * @param delay 延迟时长
     * @param task  任务体
     * @return 任务句柄
     */
    ManagedTask schedule(Duration delay, PluginTask task);

    /**
     * 按固定周期重复执行（首次延迟一个周期后触发）。
     *
     * @param period 周期时长
     * @param task   任务体
     * @return 任务句柄（取消即停止后续触发）
     */
    ManagedTask scheduleAtRate(Duration period, PluginTask task);
}
