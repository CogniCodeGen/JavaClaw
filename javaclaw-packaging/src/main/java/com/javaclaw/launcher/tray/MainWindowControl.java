package com.javaclaw.launcher.tray;

/** 托盘打开 SDK-only Desktop 进程的窄端口。 */
public interface MainWindowControl {
    /** @return 主窗口子进程仍存活时为 true */
    boolean running();

    /**
     * 打开主窗口；已有窗口时实现不得创建重复进程。
     *
     * @param onExit 子进程退出后的回调
     * @throws Exception 启动 Desktop 失败
     */
    void open(Runnable onExit) throws Exception;
}
