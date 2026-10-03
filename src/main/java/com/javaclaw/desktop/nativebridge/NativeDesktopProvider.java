package com.javaclaw.desktop.nativebridge;

import com.javaclaw.desktop.api.DesktopAvailability;
import com.javaclaw.desktop.api.DesktopApplicationLaunch;
import com.javaclaw.desktop.api.DesktopTarget;
import com.javaclaw.desktop.spi.DesktopPlatformProvider;
import com.javaclaw.desktop.spi.DesktopPlatformSession;
import com.javaclaw.platform.data.ApplicationHome;
import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** OS-gated provider backed by one versioned, signed-distribution C bridge. */
public final class NativeDesktopProvider implements DesktopPlatformProvider {
    private final ApplicationHome home;
    private final String id;
    private final String osPrefix;
    private final String library;
    private final Map<String, DesktopBridge.NativeWindow> known = new ConcurrentHashMap<>();
    private volatile DesktopBridge bridge;

    public static NativeDesktopProvider macos(ApplicationHome home) {
        return new NativeDesktopProvider(home, "macos", "mac", "libjavaclaw_desktop.dylib");
    }

    public static NativeDesktopProvider windows(ApplicationHome home) {
        return new NativeDesktopProvider(home, "windows", "windows", "javaclaw_desktop.dll");
    }

    private NativeDesktopProvider(ApplicationHome home, String id, String osPrefix, String library) {
        this.home = Objects.requireNonNull(home);
        this.id = id;
        this.osPrefix = osPrefix;
        this.library = library;
    }

    @Override public String id() { return id; }

    @Override public DesktopAvailability probe() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        if (!os.startsWith(osPrefix))
            return new DesktopAvailability(false, id, 0, "当前系统不是 " + id);
        if (!(arch.equals("aarch64") || arch.equals("arm64") || arch.equals("x86_64")
                || arch.equals("amd64")))
            return new DesktopAvailability(false, id, 0, "不支持的 CPU 架构: " + arch);
        if (id.equals("macos")) {
            String major = System.getProperty("os.version", "0").split("\\.")[0];
            try {
                if (Integer.parseInt(major) < 14)
                    return new DesktopAvailability(false, id, 0, "需要 macOS 14 或更新版本");
            } catch (NumberFormatException ignored) {
                return new DesktopAvailability(false, id, 0, "无法识别 macOS 版本");
            }
        }
        try {
            return NativeDesktopSystemPermissionService.requireCapture(bridge().probe(id));
        }
        catch (RuntimeException | LinkageError failure) {
            return new DesktopAvailability(false, id, 0, failure.getMessage());
        }
    }

    @Override public List<DesktopTarget> discoverTargets() {
        List<DesktopBridge.NativeWindow> windows = bridge().listWindows();
        known.clear();
        return windows.stream().map(window -> {
            String targetId = opaqueId(window);
            known.put(targetId, window);
            return target(targetId, window);
        }).toList();
    }

    @Override public DesktopApplicationLaunch launchApplication(String application) {
        return bridge().launchApplication(application);
    }

    @Override public java.util.Optional<Boolean> isLaunchedApplicationRunning(DesktopApplicationLaunch launch) {
        if (launch == null || launch.processId() <= 0) return java.util.Optional.empty();
        try {
            boolean alive = ProcessHandle.of(launch.processId()).map(ProcessHandle::isAlive).orElse(false);
            // ShellExecuteEx may return a short-lived launcher that hands off to
            // another process. Its exit cannot establish application termination.
            return alive ? java.util.Optional.of(true)
                    : id.equals("macos") ? java.util.Optional.of(false) : java.util.Optional.empty();
        }
        catch (RuntimeException unavailable) { return java.util.Optional.empty(); }
    }

    @Override public com.javaclaw.desktop.api.DesktopApplicationCatalog discoverApplications() {
        return bridge().listApplications();
    }

    @Override public DesktopPlatformSession open(DesktopTarget target) {
        if (!id.equals(target.providerId())) throw new IllegalArgumentException("目标平台不匹配");
        DesktopBridge.NativeWindow window = known.get(target.id());
        if (window == null || window.processId() != target.processId()
                || window.processInstanceId() == 0)
            throw new IllegalArgumentException("目标已过期，请重新发现窗口");
        DesktopBridge nativeBridge = bridge();
        MemorySegment handle = nativeBridge.open(window);
        return new NativeSession(nativeBridge, target.id(), handle);
    }

    private DesktopBridge bridge() {
        DesktopBridge existing = bridge;
        if (existing != null) return existing;
        synchronized (this) {
            if (bridge != null) return bridge;
            try { return bridge = new DesktopBridge(home, id, library); }
            catch (IOException failure) { throw new IllegalStateException(failure.getMessage(), failure); }
        }
    }

    private String opaqueId(DesktopBridge.NativeWindow window) {
        String seed = id + ":" + window.processId() + ":" + window.windowId()
                + ":" + Long.toUnsignedString(window.processInstanceId());
        return UUID.nameUUIDFromBytes(seed.getBytes(StandardCharsets.UTF_8)).toString();
    }

    private DesktopTarget target(String targetId, DesktopBridge.NativeWindow window) {
        return new DesktopTarget(id, targetId, window.processId(), window.application(),
                window.title(), window.x(), window.y(), window.width(), window.height(),
                window.flags(), window.applicationId());
    }

    private final class NativeSession implements DesktopPlatformSession {
        private final DesktopBridge bridge;
        private final String targetId;
        private final MemorySegment handle;
        private boolean closed;

        NativeSession(DesktopBridge bridge, String targetId, MemorySegment handle) {
            this.bridge = bridge;
            this.targetId = targetId;
            this.handle = handle;
        }

        @Override public synchronized DesktopTarget currentTarget() {
            requireOpen();
            return target(targetId, bridge.current(handle));
        }

        @Override public synchronized java.util.Optional<com.javaclaw.desktop.api.DesktopFrame> pollFrame(
                int timeoutMillis) {
            requireOpen();
            return bridge.poll(handle, targetId, timeoutMillis);
        }

        @Override public synchronized java.util.List<com.javaclaw.desktop.api.DesktopElement> elements(
                com.javaclaw.desktop.api.DesktopFrame frame) {
            requireOpen();
            return bridge.elements(handle, frame);
        }

        @Override public synchronized String elementDiagnostics() {
            requireOpen();
            return bridge.elementDiagnostics(handle);
        }

        @Override public synchronized void prepareForeground() {
            requireOpen();
            bridge.prepareForeground(handle);
        }

        @Override public synchronized void restoreForeground() {
            if (!closed) bridge.restoreForeground(handle);
        }

        @Override public synchronized com.javaclaw.desktop.api.DesktopActionResult perform(
                com.javaclaw.desktop.api.DesktopAction action, boolean foreground) {
            requireOpen();
            return bridge.perform(handle, action, foreground);
        }

        @Override public synchronized void close() {
            if (closed) return;
            closed = true;
            bridge.close(handle);
        }

        private void requireOpen() {
            if (closed) throw new IllegalStateException("native desktop session is closed");
        }
    }
}
