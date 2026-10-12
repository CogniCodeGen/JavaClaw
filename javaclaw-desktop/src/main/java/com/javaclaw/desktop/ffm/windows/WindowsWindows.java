package com.javaclaw.desktop.ffm.windows;

import com.javaclaw.desktop.nativebridge.DesktopBridge.NativeWindow;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import static com.javaclaw.desktop.ffm.windows.Win32.*;

/** Window handles are always paired with PID and process creation FILETIME. */
final class WindowsWindows {
    final Win32 win;
    WindowsWindows(Win32 win) { this.win = win; }

    List<NativeWindow> list() {
        List<NativeWindow> result = new ArrayList<>();
        try (Arena arena = Arena.ofConfined()) {
            var callback = MethodHandles.lookup().findVirtual(WindowsWindows.class, "enumerate",
                    MethodType.methodType(int.class, List.class, MemorySegment.class, MemorySegment.class))
                    .bindTo(this).bindTo(result);
            MemorySegment stub = Linker.nativeLinker().upcallStub(callback, FunctionDescriptor.of(I, P, P), arena);
            if (win.integer("user32", "EnumWindows", new MemoryLayout[]{P, P}, stub, MemorySegment.NULL) == 0)
                throw new IllegalStateException("Windows window enumeration failed");
        } catch (ReflectiveOperationException failure) { throw new IllegalStateException(failure); }
        return List.copyOf(result);
    }

    private int enumerate(List<NativeWindow> result, MemorySegment window, MemorySegment unused) {
        // Exceptions must never unwind through a native callback.
        try {
            if (result.size() < 256 && eligible(window.address())) {
                long pid = pid(window.address());
                long identity = instance(pid);
                NativeWindow candidate = describe(pid, window.address(), identity);
                if (pid != 0 && identity != 0 && candidate.width() > 1 && candidate.height() > 1)
                    result.add(candidate);
            }
        } catch (Throwable ignored) { /* Never unwind Java exceptions/errors through a native callback. */ }
        return 1;
    }

    private boolean eligible(long window) {
        if (!visible(window)) return false;
        long extendedStyle = (long) invoke(win.function("user32", "GetWindowLongPtrW", L, P, I), handle(window), -20);
        boolean cloaked;
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment state = arena.allocate(I);
            int status = win.integer("dwmapi", "DwmGetWindowAttribute", new MemoryLayout[]{P, I, P, I},
                    handle(window), 14, state, 4);
            cloaked = status >= 0 && state.get(I, 0) != 0;
        }
        return eligibleWindow(true, extendedStyle, title(window), cloaked);
    }

    static boolean eligibleWindow(boolean visible, long extendedStyle, String title, boolean cloaked) {
        return visible && (extendedStyle & 0x80) == 0 && title != null && !title.isEmpty() && !cloaked;
    }

    long pid(long window) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment pid = arena.allocate(I);
            win.integer("user32", "GetWindowThreadProcessId", new MemoryLayout[]{P, P}, handle(window), pid);
            return Integer.toUnsignedLong(pid.get(I, 0));
        }
    }

    long instance(long pid) {
        MemorySegment process = win.pointer("kernel32", "OpenProcess", new MemoryLayout[]{I, I, I}, 0x101000, 0, (int) pid);
        if (nullPointer(process)) return 0;
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment times = arena.allocate(32, 8);
            if (win.integer("kernel32", "WaitForSingleObject", new MemoryLayout[]{P, I}, process, 0) != 258
                    || win.integer("kernel32", "GetProcessTimes", new MemoryLayout[]{P, P, P, P, P}, process,
                    times, times.asSlice(8), times.asSlice(16), times.asSlice(24)) == 0) return 0;
            return times.get(L, 0);
        } finally { win.integer("kernel32", "CloseHandle", new MemoryLayout[]{P}, process); }
    }

    boolean valid(long pid, long window, long instance) {
        return pid > 0 && window != 0 && instance != 0
                && win.integer("user32", "IsWindow", new MemoryLayout[]{P}, handle(window)) != 0
                && pid(window) == pid && instance(pid) == instance;
    }

    NativeWindow describe(long pid, long window, long identity) {
        Rect rect = bounds(window);
        long owner = win.pointer("user32", "GetWindow", new MemoryLayout[]{P, I}, handle(window), 4).address();
        boolean related = owner != 0 && pid(owner) == pid;
        String application = processName(pid);
        int flags = (iconic(window) ? 1 : 0) | (visible(window) ? 2 : 0) | (related ? 4 : 0);
        return new NativeWindow(pid, window, identity, rect.left(), rect.top(), rect.width(), rect.height(),
                flags, application, title(window), application.toLowerCase(Locale.ROOT), related ? owner : 0, related ? 1 : 0);
    }

    Rect bounds(long window) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment prior = win.pointer("user32", "SetThreadDpiAwarenessContext", new MemoryLayout[]{P}, handle(-4));
            try {
                MemorySegment value = arena.allocate(16, 4);
                int dwm = win.integer("dwmapi", "DwmGetWindowAttribute", new MemoryLayout[]{P, I, P, I}, handle(window), 9, value, 16);
                if (dwm < 0 && win.integer("user32", "GetWindowRect", new MemoryLayout[]{P, P}, handle(window), value) == 0)
                    throw new IllegalStateException("Target window bounds unavailable");
                return new Rect(value.get(I, 0), value.get(I, 4), value.get(I, 8), value.get(I, 12));
            } finally {
                if (!nullPointer(prior)) win.pointer("user32", "SetThreadDpiAwarenessContext", new MemoryLayout[]{P}, prior);
            }
        }
    }

    boolean visible(long window) { return win.integer("user32", "IsWindowVisible", new MemoryLayout[]{P}, handle(window)) != 0; }
    boolean iconic(long window) { return win.integer("user32", "IsIconic", new MemoryLayout[]{P}, handle(window)) != 0; }
    long foreground() { return win.pointer("user32", "GetForegroundWindow", new MemoryLayout[0]).address(); }

    long popup(long pid, long root) {
        long popup = win.pointer("user32", "GetLastActivePopup", new MemoryLayout[]{P}, handle(root)).address();
        if (popup != 0 && popup != root && pid(popup) == pid && visible(popup) && !iconic(popup)) return popup;
        return root;
    }

    private String title(long window) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment text = arena.allocate(1026, 2);
            win.integer("user32", "GetWindowTextW", new MemoryLayout[]{P, P, I}, handle(window), text, 513);
            return wideString(text, 512);
        }
    }

    String processName(long pid) {
        MemorySegment process = win.pointer("kernel32", "OpenProcess", new MemoryLayout[]{I, I, I}, 0x1000, 0, (int) pid);
        if (nullPointer(process)) return "process " + pid;
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment text = arena.allocate(65536, 2);
            MemorySegment size = arena.allocate(I);
            size.set(I, 0, 32768);
            if (win.integer("kernel32", "QueryFullProcessImageNameW", new MemoryLayout[]{P, I, P, P}, process, 0, text, size) == 0)
                return "process " + pid;
            String path = wideString(text, Math.min(size.get(I, 0), 32768));
            return path.substring(Math.max(path.lastIndexOf('\\'), path.lastIndexOf('/')) + 1);
        } finally { win.integer("kernel32", "CloseHandle", new MemoryLayout[]{P}, process); }
    }

    record Rect(int left, int top, int right, int bottom) {
        int width() { return Math.max(0, right - left); }
        int height() { return Math.max(0, bottom - top); }
        boolean contains(int x, int y) { return x >= left && y >= top && x < right && y < bottom; }
    }
}
