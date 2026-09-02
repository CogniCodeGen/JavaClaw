package com.javaclaw.launcher.tray;

import com.javaclaw.protocol.LocalTransport;

/** Unix/Windows 发行 supervisor 向托盘暴露的最小进程与 transport 端口。 */
public interface TrayServerProcess {
    /**
     * @return 本地 transport 当前可连接时为 true
     * @throws Exception 平台探测失败
     */
    boolean running() throws Exception;

    /**
     * 启动或复用 App Server。
     *
     * @throws Exception 启动失败
     */
    void start() throws Exception;

    /** @return 指向当前用户 App Server 的本地 transport */
    LocalTransport transport();
}
