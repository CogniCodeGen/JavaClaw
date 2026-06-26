package com.javaclaw.desktop;

/**
 * 桌面自动化能力探测结果 —— 当前机器在"不额外安装东西"的前提下能做到哪几档。
 *
 * <p>这是"完全跨平台 = 处处可用、能力随环境优雅伸缩"的承载体：上层据此决定走精确路线
 * 还是降级路线，并据 {@link #note} 提示用户补齐缺失项（如 macOS 授权、Linux 安装 wmctrl）。</p>
 *
 * <p>能力分两档来源：</p>
 * <ul>
 *   <li><b>纯 Java 基座</b>（{@link #canCaptureScreen} / {@link #canInjectInput}）：由 AWT Robot 提供，
 *       任意桌面 OS 默认具备，是"操作任意软件"的底线；</li>
 *   <li><b>原生增强</b>（{@link #canDiscoverWindows} / {@link #canActivate} / {@link #canLaunch} /
 *       {@link #accessibilityAvailable}）：由各 OS 的 CLI 适配器提供，缺失时自动降级到基座。</li>
 * </ul>
 *
 * @param platform              平台标识（mac / windows / linux / unknown）
 * @param canDiscoverWindows    能否枚举窗口列表
 * @param canActivate           能否把指定窗口激活到前台
 * @param canLaunch             能否按名称 / 路径启动外部程序
 * @param canCaptureScreen      能否截屏（纯 Java 基座，几乎恒为 true）
 * @param canInjectInput        能否注入键鼠（纯 Java 基座；Wayland / 缺权限时为 false）
 * @param accessibilityAvailable 能否读取无障碍元素树（Tier 2，v1 暂未接入恒为 false）
 * @param note                  人类可读的降级 / 授权提示，用于引导用户补齐能力
 */
public record Capabilities(
        String platform,
        boolean canDiscoverWindows,
        boolean canActivate,
        boolean canLaunch,
        boolean canCaptureScreen,
        boolean canInjectInput,
        boolean accessibilityAvailable,
        String note) {

    /** 渲染为多行可读报告，供 {@code desktop_probe} 工具直接回显。 */
    public String report() {
        StringBuilder sb = new StringBuilder();
        sb.append("平台: ").append(platform).append('\n');
        sb.append(line("枚举窗口", canDiscoverWindows));
        sb.append(line("激活窗口", canActivate));
        sb.append(line("启动程序", canLaunch));
        sb.append(line("屏幕截图", canCaptureScreen));
        sb.append(line("键鼠注入", canInjectInput));
        sb.append(line("无障碍树", accessibilityAvailable));
        if (note != null && !note.isBlank()) {
            sb.append("提示: ").append(note);
        }
        return sb.toString().stripTrailing();
    }

    private static String line(String name, boolean ok) {
        return "  " + (ok ? "✓ " : "✗ ") + name + '\n';
    }
}
