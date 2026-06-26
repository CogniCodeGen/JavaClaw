package com.javaclaw.plugin.api.exec;

/**
 * 托管任务句柄 —— {@code PluginExecutor.submit/schedule/scheduleAtRate} 的返回物。
 *
 * <p>插件只拿到这枚"遥控器"，碰不到底层 {@code Future}/{@code Thread}。宿主在注册表中持有同一句柄，
 * 停用插件时统一取消。</p>
 *
 * @author JavaClaw
 */
public interface ManagedTask {

    /**
     * 请求取消该任务（中断承载的虚拟线程；周期任务则停止后续触发）。幂等。
     */
    void cancel();

    /**
     * @return 任务是否已结束（正常完成、异常终止或被取消）
     */
    boolean isDone();
}
