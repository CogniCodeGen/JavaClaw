package com.javaclaw.desktop;

import java.util.List;
import java.util.Map;

/**
 * 桌面启动时使用的不可变基础设施默认值；不包含模型凭据，不改变进程环境或业务配置。 显式配置的 JAVACLAW 环境变量优先于这些自动定位的默认值。
 *
 * @param appServerCommand App Server 的完整 argv，不可为 null；空列表表示沿用现有环境或发行目录定位
 * @param infrastructureEnvironment 传给本次创建的后台进程的非秘密环境默认值，不可为 null
 * @param windowsTransportCommand Windows Native Host 的 argv，不可为 null；空列表表示沿用发行目录定位
 */
public record DesktopLaunchConfiguration(
        List<String> appServerCommand,
        Map<String, String> infrastructureEnvironment,
        List<String> windowsTransportCommand) {
    /** 对命令和环境做防御性复制，拒绝 null 元素；不启动进程或访问文件系统。 */
    public DesktopLaunchConfiguration {
        appServerCommand = List.copyOf(appServerCommand);
        infrastructureEnvironment = Map.copyOf(infrastructureEnvironment);
        windowsTransportCommand = List.copyOf(windowsTransportCommand);
    }

    /** 返回不覆盖现有环境与发行目录发现逻辑的配置；适用于已有的 Desktop 嵌入入口。 */
    public static DesktopLaunchConfiguration automatic() {
        return new DesktopLaunchConfiguration(List.of(), Map.of(), List.of());
    }
}
