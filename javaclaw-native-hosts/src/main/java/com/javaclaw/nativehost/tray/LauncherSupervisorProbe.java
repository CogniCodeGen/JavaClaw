package com.javaclaw.nativehost.tray;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

import com.javaclaw.nativehost.startup.UserLoginStartup;

/** 只读判断发行 launcher 与托盘 supervisor 是否真实可用于控制 App Server。 */
public final class LauncherSupervisorProbe {
    private final String configuredLauncher;
    private final TrayPresenceProbe tray;

    /**
     * 创建读取当前进程属性与当前用户托盘心跳的探针。
     *
     * @return 不会启动进程或加载 AWT 的只读探针
     */
    public static LauncherSupervisorProbe currentUser() {
        return new LauncherSupervisorProbe(
                System.getProperty(UserLoginStartup.LAUNCHER_PROPERTY, ""), TrayPresenceProbe.currentUser());
    }

    LauncherSupervisorProbe(String configuredLauncher, TrayPresenceProbe tray) {
        this.configuredLauncher =
                Objects.requireNonNull(configuredLauncher, "configuredLauncher").strip();
        this.tray = Objects.requireNonNull(tray, "tray");
    }

    /**
     * 验证 launcher 文件与新鲜托盘心跳。
     *
     * <p>实现说明：App Server 进程不得加载 AWT；真实 {@code SystemTray} 能力只由发行 launcher 检测并通过心跳证明。
     *
     * @return 可公开给本地客户端的脱敏状态
     */
    public Status status() {
        Optional<String> launcherFailure = launcherFailure();
        if (launcherFailure.isPresent()) {
            return new Status(false, false, false, launcherFailure);
        }
        TrayPresenceStatus presence = tray.status();
        if (!presence.active()) {
            return new Status(true, false, false, presence.unavailableReason());
        }
        return new Status(true, true, true, Optional.empty());
    }

    private Optional<String> launcherFailure() {
        if (configuredLauncher.isEmpty()) {
            return Optional.of("IDEA 调试未配置 launcher supervisor");
        }
        Path launcher;
        try {
            launcher = Path.of(configuredLauncher).normalize();
        } catch (RuntimeException invalidPath) {
            return Optional.of("发行 launcher 配置无效");
        }
        try {
            if (!launcher.isAbsolute()
                    || Files.isSymbolicLink(launcher)
                    || !Files.isRegularFile(launcher, LinkOption.NOFOLLOW_LINKS)) {
                return Optional.of("发行 launcher 不存在或不是可信绝对文件");
            }
        } catch (SecurityException denied) {
            return Optional.of("发行 launcher 无法安全验证");
        }
        return Optional.empty();
    }

    /**
     * @param launcherConfigured 是否配置并验证了发行 launcher 文件
     * @param trayActive 是否存在新鲜且进程存活的托盘心跳
     * @param serverControlAvailable 是否允许托盘控制 App Server
     * @param unavailableReason 不可用时的脱敏原因
     */
    public record Status(
            boolean launcherConfigured,
            boolean trayActive,
            boolean serverControlAvailable,
            Optional<String> unavailableReason) {
        /** 校验投影字段一致。 */
        public Status {
            unavailableReason = Objects.requireNonNull(unavailableReason, "unavailableReason");
            if ((trayActive && !launcherConfigured)
                    || serverControlAvailable != (launcherConfigured && trayActive)
                    || serverControlAvailable == unavailableReason.isPresent()) {
                throw new IllegalArgumentException("launcher supervisor status fields disagree");
            }
        }
    }
}
