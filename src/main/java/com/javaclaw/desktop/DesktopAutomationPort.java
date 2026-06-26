package com.javaclaw.desktop;

import java.util.List;

/**
 * 桌面自动化端口 —— 领域层与具体 OS 实现之间的<b>唯一缝隙</b>。
 *
 * <p>本端口只承载"必须依赖操作系统能力"的那部分动作：枚举窗口、激活窗口、启动程序、
 * （未来）读无障碍树。键鼠注入与截屏不在此处——它们由纯 Java 的 {@link RobotInput} 跨平台提供，
 * 与 OS 无关，无需走端口。这条划分正是"完全跨平台"的关键：把底线压在纯 Java 基座上，
 * 把平台差异收敛到一个可替换的适配器后面。</p>
 *
 * <p>实现策略（默认）：每个 OS 一个基于命令行的适配器
 * （macOS=osascript、Windows=PowerShell、Linux=wmctrl/xdotool），免 JNA、免原生依赖、子进程隔离。
 * 将来若某平台被延迟或句柄稳定性卡住，只需在本端口背后<b>局部</b>替换为 JNA 适配器，
 * 领域代码、专家、工具一行不改。</p>
 *
 * @see DesktopAutomation 工厂：按 {@code os.name} 选择适配器
 * @see RobotInput 跨平台键鼠与截屏基座（不走本端口）
 */
public interface DesktopAutomationPort {

    /**
     * 探测当前机器的实际能力（绝不抛异常）。
     *
     * <p>会真实地试探一次底层 CLI（如 macOS 跑一条 System Events 查询以检测无障碍授权），
     * 以便"早暴露"权限 / 缺工具问题，而非等到运行中静默失败。</p>
     */
    Capabilities probe();

    /**
     * 启动外部程序，可选同时打开一个文件 / 目录。
     *
     * @param app  应用名或可执行文件路径（如 macOS 的 {@code "Visual Studio Code"}）；可空
     * @param path 要打开的文件 / 目录；可空。两者不可同时为空
     * @throws DesktopException 启动失败
     */
    void launch(String app, String path);

    /**
     * 枚举当前可见窗口（前台应用的窗口）。
     *
     * @return 窗口列表；无可枚举窗口时返回空列表
     * @throws DesktopException 枚举失败（如 macOS 未授予辅助功能权限）
     */
    List<WindowRef> listWindows();

    /**
     * 把指定窗口所属的应用激活到前台（注入键鼠前的必要前置动作）。
     *
     * @throws DesktopException 激活失败
     */
    void activate(WindowRef window);

    /**
     * 读取指定窗口的无障碍元素树（Tier 2 结构化定位）。
     *
     * <p>默认返回空列表 —— v1 尚未接入；上层据此自动降级到"整窗截图 + 视觉模型"路线。
     * 将来某适配器实现此方法即可无缝提升定位精度，上层无感知。</p>
     *
     * @return 元素列表；不支持时为空
     */
    default List<UiElement> snapshot(WindowRef window) {
        return List.of();
    }

    /** 平台标识（mac / windows / linux / unknown），用于日志与能力报告。 */
    String platform();
}
