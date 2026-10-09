package com.javaclaw.desktop.ffm.macos;

import com.javaclaw.desktop.nativebridge.DesktopBridge.NativeWindow;
import java.lang.foreign.Arena;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Window discovery and process birth identity from public macOS APIs. */
final class MacWindows {
    private final MacNative api;
    MacWindows(MacNative api) { this.api = api; }

    long instance(long pid) {
        if (pid <= 0 || pid > Integer.MAX_VALUE) return 0;
        try (Arena arena = Arena.ofConfined()) {
            // proc_bsdinfo is a stable 136-byte public libproc structure on 64-bit macOS.
            MemorySegment info = arena.allocate(136, 8);
            int bytes = (int) api.call("proc_pidinfo", ValueLayout.JAVA_INT,
                    new MemoryLayout[]{ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
                            ValueLayout.JAVA_LONG, ValueLayout.ADDRESS, ValueLayout.JAVA_INT},
                    (int) pid, 3, 0L, info, 136);
            if (bytes != 136 || info.get(ValueLayout.JAVA_INT, 12) != (int) pid) return 0;
            long seconds = info.get(ValueLayout.JAVA_LONG, 120);
            long micros = info.get(ValueLayout.JAVA_LONG, 128);
            return birthIdentity(seconds, micros);
        }
    }

    static long birthIdentity(long seconds, long micros) {
        if (seconds <= 0 || micros < 0 || micros >= 1_000_000
                || seconds > (Long.MAX_VALUE - micros) / 1_000_000) return 0;
        return seconds * 1_000_000 + micros;
    }

    List<NativeWindow> list() {
        try (var pool = api.pool()) {
            return new MacAccessibility(api).decorate(discoverableWindows(metadata()));
        }
    }

    static List<NativeWindow> discoverableWindows(List<NativeWindow> metadata) {
        // Hidden and minimized windows remain in the internal inventory, but are
        // not selectable targets and must not enter discovery's AX decoration.
        return metadata.stream().filter(window -> (window.flags() & 2) != 0).toList();
    }

    private List<NativeWindow> metadata() {
        // CG metadata discovery is permission-neutral. ScreenCaptureKit can display
        // a TCC prompt when called without capture permission, so reserve it for capture.
        // Keep All here: a bound window may still exist while hidden or minimized.
        try (var pool = api.pool()) {
            MemorySegment infos = cgWindows(0, 0);
            if (MacNative.nil(infos)) return List.of(); // No reachable WindowServer in a headless process.
            try {
                List<NativeWindow> found = new ArrayList<>();
                for (MemorySegment info : api.array(infos, 4096)) {
                    long pid = dictionaryNumber(info, "kCGWindowOwnerPID");
                    if (!MacTargetPolicy.externalProcess(pid)) continue;
                    long id = dictionaryNumber(info, "kCGWindowNumber");
                    long birth = instance(pid);
                    double[] rect = dictionaryBounds(info);
                    long layer = dictionaryNumber(info, "kCGWindowLayer");
                    if (birth == 0 || id <= 0 || rect == null || !validRect(rect)
                            || rect[2] < 24 || rect[3] < 24 || layer < 0) continue;
                    MemorySegment owner = api.object(api.cls("NSRunningApplication"),
                            "runningApplicationWithProcessIdentifier:", (int) pid);
                    String bundle = api.text(api.object(owner, "bundleIdentifier"));
                    String title = dictionaryText(info, "kCGWindowName");
                    if (foreignStatusItem(bundle, title)) continue;
                    int flags = dictionaryNumber(info, "kCGWindowIsOnscreen") != 0 ? 2 : 0;
                    if (layer > 0) flags |= 4;
                    if (bundle.equals("com.apple.controlcenter") || bundle.equals("com.apple.systemuiserver")) flags |= 8;
                    String name = api.text(api.object(owner, "localizedName"));
                    if (name.isBlank()) name = dictionaryText(info, "kCGWindowOwnerName");
                    found.add(new NativeWindow(pid, id, birth, rounded(rect[0]), rounded(rect[1]),
                            rounded(rect[2]), rounded(rect[3]), flags, name, title, bundle, 0, 0));
                    if (found.size() >= 512) break;
                }
                return found.stream().filter(window -> instance(window.processId()) == window.processInstanceId()).toList();
            } finally { api.release(infos); }
        }
    }

    <T> T withWindow(NativeWindow target, java.util.function.Function<MemorySegment, T> operation) {
        requireOriginal(target);
        if ((byte) api.call("CGPreflightScreenCaptureAccess", ValueLayout.JAVA_BYTE, new MemoryLayout[0]) == 0)
            throw new SecurityException("Screen recording permission is required");
        try (var pool = api.pool(); MacAsync callback = new MacAsync(api)) {
            api.send(api.cls("SCShareableContent"), "getShareableContentWithCompletionHandler:",
                    callback.pointer());
            var result = callback.await(Duration.ofSeconds(5));
            if (MacNative.nil(result.pointer()))
                throw new IllegalStateException("ScreenCaptureKit unavailable: " + result.error());
            for (MemorySegment window : api.array(api.object(result.pointer(), "windows"), 4096)) {
                if (api.integer(window, "windowID") == (int) target.windowId()
                        && api.integer(api.object(window, "owningApplication"), "processID") == target.processId()) {
                    requireOriginal(target);
                    return operation.apply(window);
                }
            }
            throw new IllegalStateException("Selected window no longer exists");
        }
    }

    NativeWindow refresh(NativeWindow target) {
        requireOriginal(target);
        try (var pool = api.pool()) {
            List<NativeWindow> allWindows = metadata();
            NativeWindow updated = originalWindow(target, allWindows);
            return new MacAccessibility(api).decorateOne(updated, allWindows);
        }
    }

    static NativeWindow originalWindow(NativeWindow target, List<NativeWindow> metadata) {
        return metadata.stream().filter(window -> window.processId() == target.processId()
                && window.windowId() == target.windowId() && window.processInstanceId() == target.processInstanceId())
                .findFirst().orElseThrow(() -> new IllegalStateException("Selected window disappeared"));
    }

    boolean geometryCurrent(NativeWindow target) {
        MemorySegment infos = cgWindows(8, (int) target.windowId());
        try {
            for (MemorySegment info : api.array(infos, 16)) {
                double[] rect = dictionaryBounds(info);
                if (dictionaryNumber(info, "kCGWindowNumber") == target.windowId()
                        && dictionaryNumber(info, "kCGWindowOwnerPID") == target.processId()
                        && dictionaryNumber(info, "kCGWindowIsOnscreen") != 0 && rect != null)
                    return rounded(rect[0]) == target.x() && rounded(rect[1]) == target.y()
                            && rounded(rect[2]) == target.width() && rounded(rect[3]) == target.height();
            }
            return false;
        } finally { api.release(infos); }
    }

    void requireOriginal(NativeWindow target) {
        MacTargetPolicy.requireExternalProcess(target.processId());
        if (target.processInstanceId() <= 0 || instance(target.processId()) != target.processInstanceId())
            throw new IllegalStateException("Target process exited or restarted");
    }

    Optional<Boolean> exists(long pid, long windowId, long birth) {
        if (pid <= 0 || windowId <= 0 || birth <= 0) return Optional.empty();
        long current = instance(pid);
        if (current == 0) return ProcessHandle.of(pid).isEmpty() ? Optional.of(false) : Optional.empty();
        if (current != birth) return Optional.of(false);
        try (var pool = api.pool()) {
            MemorySegment infos = cgWindows(8, (int) windowId); // including exact window
            if (MacNative.nil(infos)) return Optional.empty();
            try {
                boolean present = false;
                for (MemorySegment info : api.array(infos, 4096)) {
                    if (dictionaryNumber(info, "kCGWindowNumber") == windowId
                            && dictionaryNumber(info, "kCGWindowOwnerPID") == pid) { present = true; break; }
                }
                if (instance(pid) != birth) return Optional.of(false);
                return Optional.of(present);
            } finally { api.release(infos); }
        } catch (RuntimeException unavailable) { return Optional.empty(); }
    }

    static boolean foreignStatusItem(String owner, String title) {
        return owner.equals("com.apple.controlcenter") && !title.equals(owner)
                && title.matches("[A-Za-z0-9_-]+(?:\\.[A-Za-z0-9_-]+){2,}");
    }

    private static boolean validRect(double[] rect) {
        for (double value : rect)
            if (!Double.isFinite(value) || value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) return false;
        return true;
    }

    private static int rounded(double number) { return Math.toIntExact(Math.round(number)); }

    MemorySegment cgWindows(int option, int windowId) {
        return (MemorySegment) api.call("CGWindowListCopyWindowInfo", ValueLayout.ADDRESS,
                new MemoryLayout[]{ValueLayout.JAVA_INT, ValueLayout.JAVA_INT}, option, windowId);
    }

    long dictionaryNumber(MemorySegment dictionary, String symbol) {
        MemorySegment key = api.symbol(symbol).reinterpret(8).get(ValueLayout.ADDRESS, 0);
        MemorySegment value = api.object(dictionary, "objectForKey:", key);
        return MacNative.nil(value) ? 0 : api.number(value, "longLongValue");
    }

    String dictionaryText(MemorySegment dictionary, String symbol) {
        MemorySegment key = api.symbol(symbol).reinterpret(8).get(ValueLayout.ADDRESS, 0);
        MemorySegment value = api.object(dictionary, "objectForKey:", key);
        return MacNative.nil(value) || !api.bool(value, "isKindOfClass:", api.cls("NSString")) ? "" : api.text(value);
    }

    double[] dictionaryBounds(MemorySegment dictionary) {
        MemorySegment key = api.symbol("kCGWindowBounds").reinterpret(8).get(ValueLayout.ADDRESS, 0);
        MemorySegment bounds = api.object(dictionary, "objectForKey:", key);
        if (MacNative.nil(bounds)) return null;
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment rect = arena.allocate(MacNative.RECT);
            byte status = (byte) api.call("CGRectMakeWithDictionaryRepresentation", ValueLayout.JAVA_BYTE,
                    new MemoryLayout[]{ValueLayout.ADDRESS, ValueLayout.ADDRESS}, bounds, rect);
            if (status == 0) return null;
            return new double[]{rect.get(ValueLayout.JAVA_DOUBLE, 0), rect.get(ValueLayout.JAVA_DOUBLE, 8),
                    rect.get(ValueLayout.JAVA_DOUBLE, 16), rect.get(ValueLayout.JAVA_DOUBLE, 24)};
        }
    }

    long frontmostWindow(long pid) {
        MemorySegment infos = cgWindows(1, 0);
        try {
            for (MemorySegment info : api.array(infos, 4096)) {
                double[] bounds = dictionaryBounds(info);
                if (dictionaryNumber(info, "kCGWindowOwnerPID") == pid && bounds != null
                        && bounds[2] >= 24 && bounds[3] >= 24
                        && dictionaryNumber(info, "kCGWindowLayer") >= 0)
                    return dictionaryNumber(info, "kCGWindowNumber");
            }
            return 0;
        } finally { api.release(infos); }
    }


}
