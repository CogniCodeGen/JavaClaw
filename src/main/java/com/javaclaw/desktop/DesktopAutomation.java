package com.javaclaw.desktop;

import com.javaclaw.desktop.adapter.LinuxCliAdapter;
import com.javaclaw.desktop.adapter.MacCliAdapter;
import com.javaclaw.desktop.adapter.NoopDesktopAdapter;
import com.javaclaw.desktop.adapter.WindowsCliAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Locale;

/**
 * 桌面自动化端口工厂 —— 按当前操作系统选择并缓存合适的适配器实现。
 *
 * <p>这是"写一次、处处伸缩"的入口：调用方只拿 {@link DesktopAutomationPort} 接口，
 * 在 macOS 自动得到 osascript 适配器、Windows 得到 PowerShell 适配器、Linux 得到 wmctrl 适配器，
 * 未知平台得到全降级的 {@link NoopDesktopAdapter}（仍能靠 {@link RobotInput} 截屏 + 键鼠）。</p>
 */
public final class DesktopAutomation {

    private static final Logger log = LoggerFactory.getLogger(DesktopAutomation.class);

    /** 进程级单例：适配器无状态、可安全共享，避免重复探测。 */
    private static volatile DesktopAutomationPort instance;

    private DesktopAutomation() {
    }

    /** 获取当前平台的桌面自动化端口（首次调用时按 {@code os.name} 选择并缓存）。 */
    public static DesktopAutomationPort get() {
        DesktopAutomationPort local = instance;
        if (local == null) {
            synchronized (DesktopAutomation.class) {
                local = instance;
                if (local == null) {
                    local = create();
                    instance = local;
                }
            }
        }
        return local;
    }

    /** 按操作系统名选择适配器（包级可见，便于测试时直接构造）。 */
    static DesktopAutomationPort create() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        DesktopAutomationPort port;
        if (os.contains("mac") || os.contains("darwin")) {
            port = new MacCliAdapter();
        } else if (os.contains("win")) {
            port = new WindowsCliAdapter();
        } else if (os.contains("nux") || os.contains("nix") || os.contains("aix")) {
            port = new LinuxCliAdapter();
        } else {
            port = new NoopDesktopAdapter(os);
        }
        log.info("桌面自动化适配器已选定: {} (os.name={})", port.platform(), os);
        return port;
    }
}
