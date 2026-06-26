package com.javaclaw.desktop.adapter;

import com.javaclaw.desktop.Capabilities;
import com.javaclaw.desktop.DesktopAutomationPort;
import com.javaclaw.desktop.DesktopException;
import com.javaclaw.desktop.WindowRef;

import java.util.List;

/**
 * 全降级适配器 —— 用于未识别的操作系统。
 *
 * <p>所有原生增强（枚举 / 激活 / 启动）一律不可用并抛 {@link DesktopException}，但这<b>不</b>意味着
 * 桌面自动化整体失效：上层的 {@link com.javaclaw.desktop.RobotInput} 基座（截屏 + 键鼠）与 OS 无关，
 * 依然可用，从而保住"整屏截图 + 视觉模型 + 坐标点击"这条操作任意软件的底线。这正是
 * "完全跨平台 = 处处可用、能力随环境优雅伸缩"的体现。</p>
 */
public final class NoopDesktopAdapter implements DesktopAutomationPort {

    private final String osName;

    public NoopDesktopAdapter(String osName) {
        this.osName = osName == null || osName.isBlank() ? "unknown" : osName;
    }

    @Override
    public String platform() {
        return "unknown(" + osName + ")";
    }

    @Override
    public Capabilities probe() {
        return new Capabilities(
                platform(),
                false,  // 枚举窗口
                false,  // 激活窗口
                false,  // 启动程序
                true,   // 截屏（Robot 基座仍可用）
                true,   // 键鼠（Robot 基座仍可用）
                false,  // 无障碍树
                "未识别的操作系统，原生窗口操作不可用；仅保留整屏截图 + 键鼠（Robot 基座）的视觉定位路线。");
    }

    @Override
    public void launch(String app, String path) {
        throw new DesktopException("当前平台不支持启动外部程序: " + platform());
    }

    @Override
    public List<WindowRef> listWindows() {
        return List.of();
    }

    @Override
    public void activate(WindowRef window) {
        throw new DesktopException("当前平台不支持激活窗口: " + platform());
    }
}
