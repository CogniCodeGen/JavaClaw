package com.javaclaw.desktop;

/** Desktop 服务端通知订阅的生命周期句柄；关闭后不会再投递事件。 */
@FunctionalInterface
public interface DesktopNotificationSubscription extends AutoCloseable {
    /** 幂等取消订阅，不关闭共享 SDK 会话。 */
    @Override
    void close();
}
