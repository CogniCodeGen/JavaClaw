package com.javaclaw.plugin.api.exec;

/**
 * 协作式取消信号。长任务应周期性检查并主动退出。
 *
 * <p>宿主停用插件时会翻转此信号（并同时中断承载循环的虚拟线程），双保险确保循环可控结束。</p>
 *
 * @author JavaClaw
 */
public interface Cancellation {

    /**
     * @return 是否已被请求取消；为 true 时循环体应尽快收尾退出
     */
    boolean isCancelled();

    /** 在已取消时抛出标准取消异常，便于长任务建立一致的检查点。 */
    default void throwIfCancelled() {
        if (isCancelled()) {
            throw new java.util.concurrent.CancellationException("插件任务已取消");
        }
    }
}
