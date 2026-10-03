package com.javaclaw.desktop.nativebridge;

import com.javaclaw.desktop.api.DesktopAvailability;
import com.javaclaw.desktop.api.DesktopSystemPermissionService;
import com.javaclaw.platform.data.ApplicationHome;
import java.io.IOException;
import java.util.Locale;
import java.util.Objects;

/** OS permission boundary for the settings switch; no request occurs during status checks. */
public final class NativeDesktopSystemPermissionService implements DesktopSystemPermissionService {
    private static final int INPUT_CAPABILITIES = DesktopAvailability.SEMANTIC_INPUT
            | DesktopAvailability.DIRECTED_INPUT | DesktopAvailability.FOREGROUND_INPUT;

    private final ApplicationHome home;
    private final Platform platform;
    private volatile DesktopBridge bridge;

    public NativeDesktopSystemPermissionService(ApplicationHome home) {
        this(home, System.getProperty("os.name", ""));
    }

    NativeDesktopSystemPermissionService(ApplicationHome home, String osName) {
        this.home = Objects.requireNonNull(home, "home");
        String os = Objects.requireNonNullElse(osName, "").toLowerCase(Locale.ROOT);
        platform = os.startsWith("mac") ? Platform.MACOS
                : os.startsWith("windows") ? Platform.WINDOWS : null;
    }

    @Override public DesktopAvailability status() {
        return inspect(false);
    }

    @Override public DesktopAvailability requestPermissions() {
        return inspect(true);
    }

    private DesktopAvailability inspect(boolean request) {
        if (platform == null) {
            return new DesktopAvailability(false, "", 0,
                    "电脑应用访问仅支持 Windows 11 和 macOS 14 或更新版本");
        }
        try {
            DesktopAvailability detected = request
                    ? bridge().requestPermissions(platform.id)
                    : bridge().probe(platform.id);
            return requireCaptureAndInput(detected);
        } catch (IOException | RuntimeException | LinkageError failure) {
            String detail = failure.getMessage();
            if (detail == null || detail.isBlank()) detail = "桌面原生能力不可用";
            return new DesktopAvailability(false, "", 0, detail);
        }
    }

    static DesktopAvailability requireCaptureAndInput(DesktopAvailability detected) {
        int flags = detected.capabilities();
        boolean capture = (flags & DesktopAvailability.CAPTURE) != 0;
        boolean input = "macos".equals(detected.providerId())
                ? (flags & (DesktopAvailability.SEMANTIC_INPUT
                    | DesktopAvailability.FOREGROUND_INPUT))
                    == (DesktopAvailability.SEMANTIC_INPUT
                        | DesktopAvailability.FOREGROUND_INPUT)
                : (flags & INPUT_CAPABILITIES) != 0;
        boolean ready = detected.available() && capture && input;
        String detail = detected.detail();
        if (!ready && detail.isBlank()) detail = "缺少窗口采集或输入所需的系统权限";
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
            if (bridge == null) bridge = new DesktopBridge(home, platform.id, platform.library);
            return bridge;
        }
    }

    private enum Platform {
        MACOS("macos", "libjavaclaw_desktop.dylib"),
        WINDOWS("windows", "javaclaw_desktop.dll");

        final String id;
        final String library;

        Platform(String id, String library) {
            this.id = id;
            this.library = library;
        }
    }
}
