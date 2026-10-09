package com.javaclaw.desktop.nativebridge;

import com.javaclaw.desktop.api.DesktopAvailability;
import com.javaclaw.desktop.api.DesktopApplicationLaunch;
import com.javaclaw.desktop.api.DesktopTarget;
import com.javaclaw.desktop.spi.DesktopPlatformProvider;
import com.javaclaw.desktop.spi.DesktopPlatformSession;
import com.javaclaw.platform.data.ApplicationHome;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** OS-gated provider backed by Java implementations and direct system FFM calls. */
public final class NativeDesktopProvider implements DesktopPlatformProvider {
    private final String id;
    private final String osPrefix;
    private volatile Map<String, DesktopBridge.NativeWindow> known = Map.of();
    // Keep a bounded identity-only history so disappearance from the on-screen
    // inventory can be checked natively; it is never reused for open or capture.
    private volatile Map<String, WindowIdentity> identityHistory = Map.of();
    private volatile DesktopBridge bridge;

    public static NativeDesktopProvider macos(ApplicationHome home) {
        return new NativeDesktopProvider(home, "macos", "mac");
    }

    public static NativeDesktopProvider windows(ApplicationHome home) {
        return new NativeDesktopProvider(home, "windows", "windows");
    }

    private NativeDesktopProvider(ApplicationHome home, String id, String osPrefix) {
        Objects.requireNonNull(home);
        this.id = id;
        this.osPrefix = osPrefix;
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
        Map<String, DesktopBridge.NativeWindow> discovered = new java.util.LinkedHashMap<>();
        windows.forEach(window -> discovered.put(opaqueId(window), window));
        Map<String, DesktopBridge.NativeWindow> snapshot = Map.copyOf(discovered);
        synchronized (this) {
            Map<String, WindowIdentity> history = new java.util.LinkedHashMap<>(identityHistory);
            discovered.forEach((targetId, window) -> {
                history.remove(targetId);
                history.put(targetId, new WindowIdentity(window.processId(), window.windowId(),
                        window.processInstanceId(), window.applicationId()));
            });
            while (history.size() > 4_096) history.remove(history.keySet().iterator().next());
            identityHistory = java.util.Collections.unmodifiableMap(history);
            known = snapshot;
        }
        return windows.stream().map(window -> target(opaqueId(window), window, snapshot)).toList();
    }

    @Override public java.util.Optional<Boolean> targetExists(DesktopTarget target) {
        if (target == null || !id.equals(target.providerId())) return java.util.Optional.empty();
        WindowIdentity window = identityHistory.get(target.id());
        if (window == null || window.processId() != target.processId()
                || window.processInstanceId() == 0
                || !window.applicationId().equals(target.applicationId())) return java.util.Optional.empty();
        try { return bridge().windowExists(window.processId(), window.windowId(), window.processInstanceId()); }
        catch (RuntimeException | LinkageError unavailable) { return java.util.Optional.empty(); }
    }

    private record WindowIdentity(long processId, long windowId, long processInstanceId, String applicationId) { }

    /** Discovery identity only: zero capture fields confer no observation or input authority. */
    @Override public java.util.Optional<com.javaclaw.desktop.api.DesktopSurfaceSnapshot> surfaceForTarget(
            DesktopTarget target) {
        if (target == null || !id.equals(target.providerId())) return java.util.Optional.empty();
        Map<String, DesktopBridge.NativeWindow> snapshot = known;
        DesktopBridge.NativeWindow window = snapshot.get(target.id());
        if (window == null || window.processId() != target.processId() || window.processInstanceId() == 0
                || !window.applicationId().equals(target.applicationId())) return java.util.Optional.empty();
        DesktopTarget actual = target(target.id(), window, snapshot);
        return java.util.Optional.of(new com.javaclaw.desktop.api.DesktopSurfaceSnapshot(id,
                runtimeId(window), opaqueId(window), target.id(), window.applicationId(), 0, 0, 0,
                actual.parentTargetId(), actual.relationProof()));
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
        if (!nativeBridge.supportsPublicApi())
            throw new IllegalStateException("系统公开桌面 API 不可用");
        DesktopBridge.Session handle = nativeBridge.open(window);
        return new NativeSession(nativeBridge, target.id(), handle, window);
    }

    private DesktopBridge bridge() {
        DesktopBridge existing = bridge;
        if (existing != null) return existing;
        synchronized (this) {
            if (bridge != null) return bridge;
            try { return bridge = new DesktopBridge(id); }
            catch (IOException failure) { throw new IllegalStateException(failure.getMessage(), failure); }
        }
    }

    private String opaqueId(DesktopBridge.NativeWindow window) {
        String seed = id + ":" + window.processId() + ":" + window.windowId()
                + ":" + Long.toUnsignedString(window.processInstanceId());
        return UUID.nameUUIDFromBytes(seed.getBytes(StandardCharsets.UTF_8)).toString();
    }

    private DesktopTarget target(String targetId, DesktopBridge.NativeWindow window) {
        return target(targetId, window, known);
    }

    private DesktopTarget target(String targetId, DesktopBridge.NativeWindow window,
            Map<String, DesktopBridge.NativeWindow> snapshot) {
        String parent = "";
        if (window.relationKind() == 1 && window.parentWindowId() != 0
                && window.parentWindowId() != window.windowId()) {
            List<DesktopBridge.NativeWindow> parents = snapshot.values().stream()
                    .filter(value -> value.windowId() == window.parentWindowId()
                            && value.processInstanceId() != 0).toList();
            if (parents.size() == 1) parent = opaqueId(parents.getFirst());
        }
        return new DesktopTarget(id, targetId, window.processId(), window.application(),
                window.title(), window.x(), window.y(), window.width(), window.height(),
                window.flags(), window.applicationId(), parent,
                parent.isEmpty() ? DesktopTarget.UNKNOWN : DesktopTarget.NATIVE_PARENT);
    }

    private String runtimeId(DesktopBridge.NativeWindow window) {
        return "desktop:process:" + id + ":" + window.processId()
                + ":" + Long.toUnsignedString(window.processInstanceId());
    }

    private final class NativeSession implements DesktopPlatformSession {
        private final DesktopBridge bridge;
        private final String targetId;
        private final DesktopBridge.Session handle;
        private final DesktopBridge.NativeWindow openedWindow;
        private final String runtimeId;
        private volatile com.javaclaw.desktop.api.DesktopSurfaceSnapshot lastSurface;
        private volatile boolean closed;

        NativeSession(DesktopBridge bridge, String targetId, DesktopBridge.Session handle, DesktopBridge.NativeWindow openedWindow) {
            this.bridge = bridge;
            this.targetId = targetId;
            this.handle = handle;
            this.openedWindow = openedWindow;
            // The system implementation binds captures to this exact window and process instance. The desktop session
            // remains the separate context/consent identity; this ID grants no authority.
            this.runtimeId = runtimeId(openedWindow);
        }

        @Override public synchronized DesktopTarget currentTarget() {
            requireOpen();
            DesktopBridge.NativeWindow current = bridge.current(handle);
            requireSelectedWindow(current);
            return target(targetId, current);
        }

        @Override public synchronized java.util.Optional<com.javaclaw.desktop.api.DesktopFrame> pollFrame(
                int timeoutMillis) {
            requireOpen();
            return bridge.pollCaptured(handle, targetId, timeoutMillis).map(captured -> {
                var frame = captured.frame();
                if (captured.windowId() == openedWindow.windowId()) {
                    DesktopBridge.NativeWindow actual = bridge.current(handle);
                    requireSelectedWindow(actual);
                    DesktopTarget current = target(targetId, actual);
                    lastSurface = new com.javaclaw.desktop.api.DesktopSurfaceSnapshot(id, runtimeId,
                            opaqueId(actual), targetId, openedWindow.applicationId(), frame.windowGeneration(),
                            frame.contentRevision(), frame.capturedAtMillis(),
                            current.parentTargetId(), current.relationProof());
                } else {
                    lastSurface = null;
                    throw new IllegalStateException("桌面会话返回了不同窗口，请重新发现并打开目标");
                }
                return frame;
            });
        }

        @Override public java.util.Optional<com.javaclaw.desktop.api.DesktopSurfaceSnapshot> currentSurface() {
            requireOpen();
            return java.util.Optional.ofNullable(lastSurface);
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

        @Override public synchronized java.util.Optional<Boolean> isTargetActive() {
            requireOpen();
            return bridge.isTargetActive(handle);
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

        private void requireSelectedWindow(DesktopBridge.NativeWindow window) {
            if (window.windowId() != openedWindow.windowId()
                    || window.processId() != openedWindow.processId()
                    || window.processInstanceId() != openedWindow.processInstanceId()) {
                lastSurface = null;
                throw new IllegalStateException("桌面窗口身份已变化，请重新发现目标");
            }
        }
    }
}
