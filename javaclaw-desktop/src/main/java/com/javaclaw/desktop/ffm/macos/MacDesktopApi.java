package com.javaclaw.desktop.ffm.macos;

import com.javaclaw.desktop.api.DesktopApplicationCatalog;
import com.javaclaw.desktop.api.DesktopApplicationLaunch;
import com.javaclaw.desktop.api.DesktopAvailability;
import com.javaclaw.desktop.api.DesktopInputPolicy;
import com.javaclaw.desktop.ffm.SystemDesktopApi;
import com.javaclaw.desktop.ffm.SystemDesktopSession;
import com.javaclaw.desktop.nativebridge.DesktopBridge.NativeWindow;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.List;
import java.util.Optional;

/** Java implementation calling only installed macOS frameworks through JDK FFM. */
public final class MacDesktopApi implements SystemDesktopApi {
    private final MacNative api = new MacNative();
    private final MacWindows windows = new MacWindows(api);
    private final MacApplications applications = new MacApplications(api, windows);

    public MacDesktopApi() { }

    @Override public DesktopAvailability availability(boolean request, DesktopInputPolicy policy) {
        try (var pool = api.pool()) {
            if (!captureApiSupported()) return new DesktopAvailability(false, "macos", 0,
                    "The public macOS 14+ ScreenCaptureKit screenshot APIs are unavailable");
            boolean capture = preflight("CGPreflightScreenCaptureAccess");
            boolean accessibility = preflight("AXIsProcessTrusted");
            boolean post = preflight("CGPreflightPostEventAccess");
            if (request) {
                if (!capture) {
                    preflight("CGRequestScreenCaptureAccess");
                    capture = preflight("CGPreflightScreenCaptureAccess");
                    if (!capture) privacy("Privacy_ScreenCapture");
                } else if (!accessibility) {
                    // Opening settings is an explicit permission request, never part of probe/open.
                    privacy("Privacy_Accessibility");
                    accessibility = preflight("AXIsProcessTrusted");
                } else if (policy == DesktopInputPolicy.SYSTEM_EXPLICIT && !post) {
                    preflight("CGRequestPostEventAccess");
                    post = preflight("CGPreflightPostEventAccess");
                    if (!post) privacy("Privacy_Accessibility");
                }
            }
            int capabilities = DesktopAvailability.PUBLIC_SEMANTIC;
            if (capture) capabilities |= DesktopAvailability.CAPTURE;
            if (accessibility) capabilities |= DesktopAvailability.SEMANTIC_INPUT;
            if (accessibility && post) capabilities |= DesktopAvailability.FOREGROUND_INPUT;
            boolean input = accessibility && (policy != DesktopInputPolicy.SYSTEM_EXPLICIT || post);
            return new DesktopAvailability(capture && input, "macos", capabilities,
                    capture && input ? "System ScreenCaptureKit and public Accessibility APIs are ready"
                            : !capture ? "Screen recording permission is required"
                            : !accessibility ? "Accessibility permission is required"
                            : "Post Event permission is required for explicit system input");
        }
    }

    private boolean captureApiSupported() {
        MemorySegment manager = api.cls("SCScreenshotManager");
        MemorySegment filter = api.cls("SCContentFilter");
        MemorySegment configuration = api.cls("SCStreamConfiguration");
        return !MacNative.nil(manager) && !MacNative.nil(filter) && !MacNative.nil(configuration)
                && api.bool(manager, "respondsToSelector:",
                        api.sel("captureImageWithFilter:configuration:completionHandler:"))
                && api.bool(filter, "instancesRespondToSelector:", api.sel("pointPixelScale"))
                && api.bool(configuration, "instancesRespondToSelector:", api.sel("setIgnoreShadowsSingleWindow:"));
    }

    private boolean preflight(String name) {
        return (byte) api.call(name, ValueLayout.JAVA_BYTE, new MemoryLayout[0]) != 0;
    }

    private void privacy(String pane) {
        MemorySegment url = api.object(api.cls("NSURL"), "URLWithString:",
                api.string("x-apple.systempreferences:com.apple.preference.security?" + pane));
        api.bool(api.object(api.cls("NSWorkspace"), "sharedWorkspace"), "openURL:", url);
    }

    @Override public List<NativeWindow> windows() { return windows.list(); }
    @Override public DesktopApplicationCatalog applications() { return applications.catalog(); }
    @Override public DesktopApplicationLaunch launch(String application) { return applications.launch(application); }
    @Override public Optional<Boolean> windowExists(long pid, long window, long birth) {
        return windows.exists(pid, window, birth);
    }
    @Override public SystemDesktopSession open(NativeWindow target) {
        MacTargetPolicy.requireExternalProcess(target.processId());
        if (!captureApiSupported()) throw new UnsupportedOperationException("macOS 14+ ScreenCaptureKit APIs are required");
        if (!preflight("CGPreflightScreenCaptureAccess")) throw new SecurityException("Screen recording permission is required");
        windows.requireOriginal(target);
        NativeWindow current = windows.refresh(target);
        if (current == null) throw new IllegalStateException("Selected window is not available");
        return new MacDesktopSession(api, windows, current);
    }
}
