package com.javaclaw.desktop.ffm.windows;

import com.javaclaw.desktop.api.DesktopApplicationCatalog;
import com.javaclaw.desktop.api.DesktopApplicationLaunch;
import com.javaclaw.desktop.api.DesktopAvailability;
import com.javaclaw.desktop.api.DesktopInputPolicy;
import com.javaclaw.desktop.ffm.SystemDesktopApi;
import com.javaclaw.desktop.ffm.SystemDesktopSession;
import com.javaclaw.desktop.nativebridge.DesktopBridge.NativeWindow;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.Arena;
import java.util.List;
import java.util.Optional;
import static com.javaclaw.desktop.ffm.windows.Win32.*;

/** Windows desktop implementation using Java and FFM directly against signed system DLLs. */
public final class WindowsDesktopApi implements SystemDesktopApi {
    private final Win32 win;
    private final WindowsWindows windows;
    private final WindowsApplications applications;

    public WindowsDesktopApi() {
        win = new Win32();
        windows = new WindowsWindows(win);
        applications = new WindowsApplications(win);
    }

    @Override public DesktopAvailability availability(boolean request, DesktopInputPolicy policy) {
        if (!supportedWindowsVersion()) return new DesktopAvailability(false, "windows", 0,
                "Windows 11 build 22000 or newer is required");
        MemorySegment desktop = win.pointer("user32", "OpenInputDesktop", new MemoryLayout[]{I, I, I}, 0, 0, 1);
        if (nullPointer(desktop)) return new DesktopAvailability(false, "windows", 0,
                "Windows input desktop is unavailable or access was denied; secure desktops are not accessible");
        win.integer("user32", "CloseDesktop", new MemoryLayout[]{P}, desktop);
        if (!WindowsCapture.supported(win)) return new DesktopAvailability(false, "windows", 0,
                "Windows Graphics Capture is unavailable on this Windows session");
        int capabilities = DesktopAvailability.CAPTURE | DesktopAvailability.SEMANTIC_INPUT
                | DesktopAvailability.PUBLIC_SEMANTIC | DesktopAvailability.FOREGROUND_INPUT;
        return new DesktopAvailability(true, "windows", capabilities,
                "Java FFM: Windows Graphics Capture, UI Automation public semantics, and explicitly authorized SendInput");
    }

    private boolean supportedWindowsVersion() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment version = arena.allocate(276, 4); // OSVERSIONINFOW: five DWORDs and 128 WCHARs.
            version.set(I, 0, 276);
            return win.integer("ntdll", "RtlGetVersion", new MemoryLayout[]{P}, version) >= 0
                    && supportedVersion(version.get(I, 4), version.get(I, 12));
        }
    }

    static boolean supportedVersion(int major, int build) { return major >= 10 && build >= 22000; }

    @Override public List<NativeWindow> windows() { return windows.list(); }
    @Override public DesktopApplicationCatalog applications() { return applications.catalog(); }
    @Override public DesktopApplicationLaunch launch(String application) { return applications.launch(application); }
    @Override public Optional<Boolean> windowExists(long pid, long window, long instance) {
        if (pid <= 0 || window == 0 || instance == 0
                || win.integer("user32", "IsWindow", new MemoryLayout[]{P}, handle(window)) == 0 || windows.pid(window) != pid)
            return Optional.of(false);
        long observed = windows.instance(pid);
        return observed == 0 ? Optional.empty() : Optional.of(observed == instance);
    }
    @Override public SystemDesktopSession open(NativeWindow window) {
        if (!windows.valid(window.processId(), window.windowId(), window.processInstanceId()))
            throw new IllegalStateException("Target window or process creation identity changed before session open");
        return new WindowsSession(windows, window);
    }
}
