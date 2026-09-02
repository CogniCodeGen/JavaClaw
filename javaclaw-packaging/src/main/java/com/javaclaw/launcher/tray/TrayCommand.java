package com.javaclaw.launcher.tray;

/** 托盘允许触发的固定产品命令；刻意不包含任何 Schedule 动作。 */
public enum TrayCommand {
    /** 打开或聚焦主窗口。 */
    OPEN_MAIN,
    /** 启动 App Server。 */
    START_SERVER,
    /** 在安全门禁通过后停止 App Server。 */
    STOP_SERVER,
    /** 在安全门禁通过后重启 App Server。 */
    RESTART_SERVER,
    /** 刷新当前状态。 */
    REFRESH
}
