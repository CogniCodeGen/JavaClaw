package com.javaclaw.desktop;

/**
 * Desktop 子进程从发行 launcher 接收的只读会话标记。
 *
 * @param supervised Desktop 是否由发行 launcher 启动
 * @param trayActive launcher 是否已提供 SystemTray 控制面
 */
public record LauncherSession(boolean supervised, boolean trayActive) {
    /** 发行 launcher 注入的 supervisor 标记。 */
    public static final String SUPERVISED_PROPERTY = "javaclaw.launcher.supervised";

    /** 发行 launcher 注入的托盘活动标记。 */
    public static final String TRAY_ACTIVE_PROPERTY = "javaclaw.launcher.tray-active";

    /** 校验托盘活动状态不会脱离 supervisor 单独出现。 */
    public LauncherSession {
        if (trayActive && !supervised) {
            throw new IllegalArgumentException("trayActive requires supervised launcher");
        }
    }

    /**
     * 从当前 JVM 的显式发行属性解析会话，不猜测安装目录或父进程。
     *
     * @return 当前 launcher 会话
     */
    public static LauncherSession current() {
        boolean supervised = Boolean.getBoolean(SUPERVISED_PROPERTY);
        boolean trayActive = supervised && Boolean.getBoolean(TRAY_ACTIVE_PROPERTY);
        return new LauncherSession(supervised, trayActive);
    }

    /**
     * 返回连接失败时可执行且不夸大能力的恢复说明。
     *
     * @return 面向用户的恢复说明
     */
    public String recoveryInstruction() {
        if (trayActive) {
            return "发行 launcher 已在打开 Desktop 前启动 App Server。若服务已离线，请从系统托盘启动或重启后重新连接。";
        }
        if (supervised) {
            return "发行 launcher 已在打开 Desktop 前启动 App Server。当前平台没有可用托盘，请重新启动 JavaClaw 后再连接。";
        }
        return "IDEA 直接运行没有 launcher supervisor；请运行“JavaClaw 一键启动（前后端）”或先启动 App Server。";
    }

    /**
     * 返回连接页只读控制项的标签。
     *
     * @return 与实际 launcher 模式一致的标签
     */
    public String controlLabel() {
        if (trayActive) {
            return "由系统托盘控制";
        }
        if (supervised) {
            return "由发行 Launcher 启动";
        }
        return "启动 App Server";
    }
}
