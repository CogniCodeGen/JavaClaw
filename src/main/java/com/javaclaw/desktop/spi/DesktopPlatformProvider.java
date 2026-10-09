package com.javaclaw.desktop.spi;

import com.javaclaw.desktop.api.DesktopAvailability;
import com.javaclaw.desktop.api.DesktopApplicationLaunch;
import com.javaclaw.desktop.api.DesktopApplicationLaunchRejectedException;
import com.javaclaw.desktop.api.DesktopTarget;
import java.util.List;

/** Host-owned platform SPI. Plugins can provide implementations without changing UI or Agent code. */
public interface DesktopPlatformProvider {
    String id();
    DesktopAvailability probe();
    List<DesktopTarget> discoverTargets();
    /** Identity from discovery only: no open/capture, no freshness or input authority. */
    default java.util.Optional<com.javaclaw.desktop.api.DesktopSurfaceSnapshot> surfaceForTarget(DesktopTarget target) {
        return java.util.Optional.empty();
    }
    /** False requires actual exact-window/process-instance destruction proof; absence from enumeration is insufficient. */
    default java.util.Optional<Boolean> targetExists(DesktopTarget target) {
        return java.util.Optional.empty();
    }
    /** Read installed application identities without starting a process. */
    default com.javaclaw.desktop.api.DesktopApplicationCatalog discoverApplications() {
        throw new UnsupportedOperationException("当前桌面平台不支持安装应用发现");
    }
    /** Launch an installed application by its exact name or bundle ID, never by a path. */
    default DesktopApplicationLaunch launchApplication(String application) {
        throw new DesktopApplicationLaunchRejectedException(
                DesktopApplicationLaunchRejectedException.Reason.UNSUPPORTED,
                "当前桌面平台不支持启动应用");
    }
    /**
     * Read-only application lifecycle proof. False proves the launched application
     * exited, not merely a short-lived launcher PID; empty forbids another launch.
     */
    default java.util.Optional<Boolean> isLaunchedApplicationRunning(DesktopApplicationLaunch launch) {
        return java.util.Optional.empty();
    }
    DesktopPlatformSession open(DesktopTarget target);
}
