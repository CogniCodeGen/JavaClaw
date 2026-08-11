package com.javaclaw.desktop;

import com.javaclaw.agent.ToolCallOrigin;

import java.nio.file.Path;
import java.util.function.Supplier;

/**
 * 为一次编排来源创建薄桌面工具门面，共享进程级 OS 端口和 AWT Robot。
 *
 * <p>工厂及其依赖由 Spring 根 Context 管理；返回的 {@link DesktopTools} 只保存本次来源令牌、
 * 截图目录和短生命周期的元素引用表，不拥有共享资源，也无需显式关闭。</p>
 */
public final class DesktopToolFactory {

    private final DesktopAutomationPort port;
    private final Supplier<RobotInput> input;

    public DesktopToolFactory(DesktopAutomationPort port, Supplier<RobotInput> input) {
        this.port = java.util.Objects.requireNonNull(port, "port");
        this.input = java.util.Objects.requireNonNull(input, "input");
    }

    public DesktopTools create(ToolCallOrigin origin, Path screenshotsDir) {
        return new DesktopTools(origin, screenshotsDir, port, input.get());
    }
}
