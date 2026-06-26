package com.javaclaw.plugin;

/**
 * 插件生命周期状态（管理器维度）。
 *
 * <pre>
 *   DISCOVERED ──load──▶ LOADED ──start──▶ ACTIVE ──stop──▶ STOPPED ──unload──▶ (移除)
 *        │                  │                 │
 *        └──────────────────┴─────────────────┴──── 任意环节失败 ──▶ FAILED
 * </pre>
 *
 * @author JavaClaw
 */
public enum PluginState {

    /** 已在 plugins/ 目录发现 jar 并读到描述符，尚未建类加载器 */
    DISCOVERED("已发现"),

    /** 已建类加载器并实例化入口类，未启用 */
    LOADED("已加载"),

    /** 已调用 start()，后台逻辑运行中 */
    ACTIVE("运行中"),

    /** 已调用 stop()，资源已回收，可再次启用 */
    STOPPED("已停用"),

    /** 加载/启动/停止过程中出错 */
    FAILED("失败");

    private final String displayName;

    PluginState(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }
}
