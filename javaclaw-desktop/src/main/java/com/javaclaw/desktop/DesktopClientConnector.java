package com.javaclaw.desktop;

import java.io.IOException;
import java.util.function.Consumer;

import com.javaclaw.client.ServerNotification;
import com.javaclaw.client.sdk.JavaClawClient;

/** Desktop 建立 SDK 会话的外部副作用边界。 */
@FunctionalInterface
public interface DesktopClientConnector {
    /**
     * 建立并完成 Protocol v3 协商。
     *
     * @param notifications 当前连接的强类型通知处理器
     * @return Desktop 独占的 SDK 会话
     * @throws IOException 本地 transport 无法连接
     */
    JavaClawClient connect(Consumer<ServerNotification> notifications) throws IOException;
}
