package com.javaclaw.desktop;

import java.awt.Rectangle;

/**
 * 窗口引用 —— 跨平台的桌面窗口稳定标识。
 *
 * <p>不同操作系统的"窗口句柄"形态各异（macOS 是进程名 + 窗口序号、Linux 是 X11 窗口 id、
 * Windows 仅能拿到标题），本记录把它们统一抽象为对上层透明的语义信息：上层只关心
 * "哪个 App 的哪个窗口、在屏幕的什么位置"，不关心底层句柄怎么编码。</p>
 *
 * <p>设计取舍：CLI 路线是<b>无状态</b>的——两次枚举之间窗口可能移动或关闭，因此本引用是
 * 某次快照时刻的值对象，不是可长期持有的句柄。需要操作时应就近重新 {@code listWindows()}。
 * 这正是 CLI 相对 JNA 原生句柄的固有取舍（见架构说明）。</p>
 *
 * @param id     适配器内部用于再定位的标识（macOS: {@code "应用名"}；Linux: X11 窗口 id；可空）
 * @param app    应用 / 进程名，用于激活窗口
 * @param title  窗口标题
 * @param bounds 屏幕逻辑坐标系下的位置与尺寸；某些平台（如 Windows 纯标题枚举）拿不到时为 {@code null}
 */
public record WindowRef(String id, String app, String title, Rectangle bounds) {

    /** 是否已知窗口的屏幕包围盒（决定能否做"单窗口区域截图"而非全屏兜底）。 */
    public boolean hasBounds() {
        return bounds != null && bounds.width > 0 && bounds.height > 0;
    }

    /** 人类可读的一行摘要，用于工具结果回显给模型 / 用户。 */
    public String describe() {
        String pos = hasBounds()
                ? String.format("@(%d,%d %dx%d)", bounds.x, bounds.y, bounds.width, bounds.height)
                : "@(位置未知)";
        return String.format("%s 「%s」 %s", app, title == null || title.isBlank() ? "(无标题)" : title, pos);
    }
}
