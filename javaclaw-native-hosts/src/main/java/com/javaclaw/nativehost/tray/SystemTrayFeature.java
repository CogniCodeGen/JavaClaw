package com.javaclaw.nativehost.tray;

import java.awt.GraphicsEnvironment;
import java.awt.SystemTray;
import java.util.Locale;
import java.util.Optional;

/** 对 macOS、Linux 与 Windows 的 AWT SystemTray 能力执行 fail-closed 检测。 */
public final class SystemTrayFeature {
    /** 只有发行启动器或原生 Runner 可以启用真实 AWT 探针。 */
    public static final String LAUNCHER_PROPERTY = "javaclaw.tray.launcher";

    private SystemTrayFeature() {}

    /**
     * 检测当前 Runtime 是否具备真实桌面托盘。
     *
     * @return 平台、可用性和脱敏原因
     */
    public static Status detect() {
        String operatingSystem = System.getProperty("os.name", "");
        Platform currentPlatform = platform(operatingSystem);
        if (!Boolean.getBoolean(LAUNCHER_PROPERTY)) {
            return new Status(currentPlatform, false, Optional.of("IDEA 调试未连接 launcher supervisor"));
        }
        boolean headless;
        boolean supported;
        try {
            headless = GraphicsEnvironment.isHeadless();
            supported = !headless && SystemTray.isSupported();
        } catch (RuntimeException | LinkageError failure) {
            return new Status(platform(operatingSystem), false, Optional.of("当前 Java Runtime 无法加载 SystemTray"));
        }
        return detect(operatingSystem, headless, supported);
    }

    static Status detect(String operatingSystem, boolean headless, boolean supported) {
        Platform platform = platform(operatingSystem);
        if (platform == Platform.UNSUPPORTED) {
            return new Status(platform, false, Optional.of("当前操作系统不在 JavaClaw 托盘支持范围"));
        }
        if (headless) {
            return new Status(platform, false, Optional.of("当前会话没有可用的图形桌面"));
        }
        if (!supported) {
            return new Status(platform, false, Optional.of("当前桌面环境不支持 SystemTray"));
        }
        return new Status(platform, true, Optional.empty());
    }

    private static Platform platform(String operatingSystem) {
        String normalized = operatingSystem.toLowerCase(Locale.ROOT);
        if (normalized.contains("mac")) {
            return Platform.MACOS;
        }
        if (normalized.contains("win")) {
            return Platform.WINDOWS;
        }
        if (normalized.contains("linux")) {
            return Platform.LINUX;
        }
        return Platform.UNSUPPORTED;
    }

    /** 受支持的平台分类。 */
    public enum Platform {
        /** macOS 菜单栏。 */
        MACOS,
        /** Linux 桌面通知区域。 */
        LINUX,
        /** Windows 通知区域。 */
        WINDOWS,
        /** 未经发布 Runner 验证的平台。 */
        UNSUPPORTED
    }

    /**
     * @param platform 平台分类
     * @param available 当前会话是否可创建 SystemTray
     * @param unavailableReason 不可用时的脱敏原因
     */
    public record Status(Platform platform, boolean available, Optional<String> unavailableReason) {
        /** 校验可用性与原因一致。 */
        public Status {
            java.util.Objects.requireNonNull(platform, "platform");
            unavailableReason = java.util.Objects.requireNonNull(unavailableReason, "unavailableReason");
            if (available == unavailableReason.isPresent()) {
                throw new IllegalArgumentException("tray availability and reason disagree");
            }
        }
    }
}
