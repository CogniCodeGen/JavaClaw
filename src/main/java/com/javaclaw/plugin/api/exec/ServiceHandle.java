package com.javaclaw.plugin.api.exec;

/**
 * 后台服务句柄 —— {@code PluginExecutor.background(...)} 的返回物，代表一个长活监听循环。
 *
 * <p>插件只拿到控制位，碰不到底层虚拟线程。宿主在注册表中持有同一句柄，停用插件时统一取消。</p>
 *
 * @author JavaClaw
 */
public interface ServiceHandle {

    /**
     * 请求停止该后台服务：翻转取消信号并中断承载的虚拟线程。幂等。
     */
    void cancel();

    /**
     * @return 后台服务是否仍在运行
     */
    boolean isRunning();
}
