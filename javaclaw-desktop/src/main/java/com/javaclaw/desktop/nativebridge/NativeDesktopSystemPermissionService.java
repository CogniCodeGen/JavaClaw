package com.javaclaw.desktop.nativebridge;

import com.javaclaw.desktop.api.DesktopAvailability;
import com.javaclaw.desktop.api.DesktopInputPolicy;
import com.javaclaw.desktop.api.DesktopSystemPermissionService;
import com.javaclaw.platform.data.ApplicationHome;
import java.io.IOException;
import java.util.Locale;
import java.util.Objects;

/** OS permission boundary for the settings switch; no request occurs during status checks. */
public final class NativeDesktopSystemPermissionService implements DesktopSystemPermissionService {
    private final Platform platform;
    private volatile DesktopBridge bridge;

    public NativeDesktopSystemPermissionService(ApplicationHome home) {
        this(home, System.getProperty("os.name", ""));
    }

    NativeDesktopSystemPermissionService(ApplicationHome home, String osName) {
        Objects.requireNonNull(home, "home");
        String os = Objects.requireNonNullElse(osName, "").toLowerCase(Locale.ROOT);
        platform = os.startsWith("mac") ? Platform.MACOS
                : os.startsWith("windows") ? Platform.WINDOWS : null;
    }

    @Override public DesktopAvailability status() {
        return status(DesktopInputPolicy.BACKGROUND_STRICT);
    }

    @Override public DesktopAvailability requestPermissions() {
        return requestPermissions(DesktopInputPolicy.BACKGROUND_STRICT);
    }

    @Override public DesktopAvailability status(DesktopInputPolicy policy) {
        return inspect(false, policy);
    }

    @Override public DesktopAvailability requestPermissions(DesktopInputPolicy policy) {
        return inspect(true, policy);
    }

    private DesktopAvailability inspect(boolean request, DesktopInputPolicy policy) {
        Objects.requireNonNull(policy, "policy");
        if (platform == null) {
            return new DesktopAvailability(false, "", 0,
                    "电脑应用访问仅支持 Windows 11 和 macOS 14 或更新版本");
        }
        try {
            DesktopAvailability detected = request
                    ? bridge().requestPermissions(platform.id, policy)
                    : bridge().probe(platform.id, policy);
            return requireCaptureAndInput(detected, policy);
        } catch (IOException | RuntimeException | LinkageError failure) {
            String detail = failure.getMessage();
            if (detail == null || detail.isBlank()) detail = "系统桌面能力不可用";
            return new DesktopAvailability(false, "", 0, detail);
        }
    }

    static DesktopAvailability requireCaptureAndInput(DesktopAvailability detected) {
        return requireCaptureAndInput(detected, DesktopInputPolicy.BACKGROUND_STRICT);
    }

    static DesktopAvailability requireCaptureAndInput(DesktopAvailability detected,
            DesktopInputPolicy policy) {
        int flags = detected.capabilities();
        int required = DesktopAvailability.CAPTURE | (policy == DesktopInputPolicy.SYSTEM_EXPLICIT
                ? DesktopAvailability.FOREGROUND_INPUT
                : DesktopAvailability.SEMANTIC_INPUT | DesktopAvailability.PUBLIC_SEMANTIC);
        boolean ready = (flags & required) == required;
        String detail = detected.detail();
        if (!ready && detail.isBlank()) detail = policy == DesktopInputPolicy.SYSTEM_EXPLICIT
                ? "缺少窗口采集或系统输入所需的权限"
                : "缺少窗口采集、辅助功能权限或公开后台语义能力";
        return new DesktopAvailability(ready, detected.providerId(), flags, detail);
    }

    /** A read-only session only needs a working capture path. */
    static DesktopAvailability requireCapture(DesktopAvailability detected) {
        boolean ready = (detected.capabilities() & DesktopAvailability.CAPTURE) != 0;
        String detail = detected.detail();
        if (!ready && detail.isBlank()) detail = "缺少窗口采集所需的系统权限";
        return new DesktopAvailability(ready, detected.providerId(), detected.capabilities(), detail);
    }

    private DesktopBridge bridge() throws IOException {
        DesktopBridge current = bridge;
        if (current != null) return current;
        synchronized (this) {
            if (bridge == null) bridge = new DesktopBridge(platform.id);
            return bridge;
        }
    }

    private enum Platform {
        MACOS("macos"),
        WINDOWS("windows");

        final String id;

        Platform(String id) {
            this.id = id;
        }
    }
}
