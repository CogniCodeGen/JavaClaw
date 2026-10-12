package com.javaclaw.desktop.ffm.macos;

import com.javaclaw.desktop.api.DesktopAction;
import com.javaclaw.desktop.api.DesktopActionResult;
import com.javaclaw.desktop.api.DesktopFrame;
import com.javaclaw.desktop.nativebridge.DesktopBridge.NativeWindow;
import java.lang.foreign.Arena;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import static com.javaclaw.desktop.api.DesktopActionResult.Status.*;

/** Explicit foreground lease and system CGEvents. All sequencing and policy are Java. */
final class MacInput {
    private static final Map<String, Integer> KEYS = Map.ofEntries(
            Map.entry("A", 0), Map.entry("B", 11), Map.entry("C", 8), Map.entry("D", 2),
            Map.entry("E", 14), Map.entry("F", 3), Map.entry("G", 5), Map.entry("H", 4),
            Map.entry("I", 34), Map.entry("J", 38), Map.entry("K", 40), Map.entry("L", 37),
            Map.entry("M", 46), Map.entry("N", 45), Map.entry("O", 31), Map.entry("P", 35),
            Map.entry("Q", 12), Map.entry("R", 15), Map.entry("S", 1), Map.entry("T", 17),
            Map.entry("U", 32), Map.entry("V", 9), Map.entry("W", 13), Map.entry("X", 7),
            Map.entry("Y", 16), Map.entry("Z", 6), Map.entry("0", 29), Map.entry("1", 18),
            Map.entry("2", 19), Map.entry("3", 20), Map.entry("4", 21), Map.entry("5", 23),
            Map.entry("6", 22), Map.entry("7", 26), Map.entry("8", 28), Map.entry("9", 25),
            Map.entry("ENTER", 36), Map.entry("RETURN", 36), Map.entry("TAB", 48), Map.entry("SPACE", 49),
            Map.entry("BACKSPACE", 51), Map.entry("DELETE", 51), Map.entry("ESCAPE", 53), Map.entry("ESC", 53),
            Map.entry("LEFT", 123), Map.entry("RIGHT", 124), Map.entry("DOWN", 125), Map.entry("UP", 126),
            Map.entry("HOME", 115), Map.entry("END", 119), Map.entry("PAGEUP", 116), Map.entry("PAGEDOWN", 121),
            Map.entry("F1", 122), Map.entry("F2", 120), Map.entry("F3", 99), Map.entry("F4", 118),
            Map.entry("F5", 96), Map.entry("F6", 97), Map.entry("F7", 98), Map.entry("F8", 100),
            Map.entry("F9", 101), Map.entry("F10", 109), Map.entry("F11", 103), Map.entry("F12", 111));
    private final MacNative api;
    private final MacWindows windows;
    private final MacAccessibility ax;
    private long previousPid;
    private long previousBirth;
    private MemorySegment previousWindow = MemorySegment.NULL;
    private boolean leased;

    MacInput(MacNative api, MacWindows windows, MacAccessibility ax) {
        this.api = api; this.windows = windows; this.ax = ax;
    }

    long activePid() {
        return api.integer(api.object(api.object(api.cls("NSWorkspace"), "sharedWorkspace"),
                "frontmostApplication"), "processIdentifier");
    }

    boolean postAllowed() {
        return (byte) api.call("CGPreflightPostEventAccess", ValueLayout.JAVA_BYTE, new MemoryLayout[0]) != 0;
    }

    void prepare(NativeWindow target) {
        windows.requireOriginal(target);
        if (!ax.trusted() || !postAllowed()) throw new SecurityException("Accessibility and Post Event permission are required");
        if (leased) {
            if (ready(target, null)) return;
            restore(target);
            throw new SecurityException("Foreground ownership changed during preparation; observe again");
        }
        previousPid = activePid();
        previousBirth = windows.instance(previousPid);
        MemorySegment oldApp = ax.application(previousPid);
        try { previousWindow = MacNative.nil(oldApp) ? MemorySegment.NULL : api.attribute(oldApp, "AXFocusedWindow"); }
        finally { api.release(oldApp); }
        leased = true;
        MemorySegment selected = ax.window(target);
        try {
            if (MacNative.nil(selected)) throw new SecurityException("Selected AX window is not uniquely identifiable");
            MemorySegment app = api.object(api.cls("NSRunningApplication"),
                    "runningApplicationWithProcessIdentifier:", (int) target.processId());
            if (MacNative.nil(app) || !api.bool(app, "activateWithOptions:", 0L))
                throw new SecurityException("Target foreground activation was rejected");
            ax.perform(selected, "AXRaise");
            for (int attempt = 0; attempt < 20 && !ready(target, null); attempt++) pause(50);
            if (!ready(target, null)) throw new SecurityException("Selected window did not gain confirmed foreground focus");
        } catch (RuntimeException failure) {
            restore(target); throw failure;
        } finally { api.release(selected); }
    }

    void restore(NativeWindow target) {
        if (!leased) return;
        leased = false;
        try {
            // Restore only a lease still owned by this target. A user focus switch wins.
            if (activePid() != target.processId() || !ax.focused(target)
                    || windows.instance(target.processId()) != target.processInstanceId()
                    || previousPid <= 0 || previousPid == target.processId()
                    || previousBirth == 0 || windows.instance(previousPid) != previousBirth) return;
            MemorySegment previous = api.object(api.cls("NSRunningApplication"),
                    "runningApplicationWithProcessIdentifier:", (int) previousPid);
            if (!MacNative.nil(previous) && api.bool(previous, "activateWithOptions:", 0L)
                    && !MacNative.nil(previousWindow)) ax.perform(previousWindow, "AXRaise");
        } finally {
            api.release(previousWindow); previousWindow = MemorySegment.NULL;
            previousPid = 0; previousBirth = 0;
        }
    }

    DesktopActionResult perform(NativeWindow target, DesktopAction action, DesktopFrame frame,
            java.util.function.BooleanSupplier secondClickUnchanged) {
        if (!leased || !postAllowed() || !ax.trusted())
            return result(DENIED, action, "Explicit foreground preparation and system input permission are required");
        if (action.kind() == DesktopAction.Kind.TYPE
                && action.textOperation() == DesktopAction.TextOperation.SET_TEXT)
            return result(UNSUPPORTED, action, "System events cannot perform semantic SET_TEXT");
        Key key = action.kind() == DesktopAction.Kind.KEY ? parseKey(action.text()) : null;
        if (action.kind() == DesktopAction.Kind.KEY && key == null)
            return result(UNSUPPORTED, action, "Unknown key or modifier name");
        double[] point = {target.x() + (action.x() + 0.5) * frame.geometry().logicalWidth() / frame.width(),
                target.y() + (action.y() + 0.5) * frame.geometry().logicalHeight() / frame.height()};
        if (!ready(target, pointerRequired(action.kind(), 0) ? point : null))
            return result(DENIED, action, "Selected target is covered, moved, or does not own keyboard/pointer focus");
        List<MemorySegment> events = new ArrayList<>();
        try (Arena arena = Arena.ofConfined()) {
            switch (action.kind()) {
                case CLICK -> mouseEvents(arena, point, action, events);
                case KEY -> keyEvents(key.code(), key.flags(), "", arena, events);
                case TYPE -> {
                    // Explicit foreground TYPE first focuses the observed control,
                    // then inserts Unicode only while this exact window retains focus.
                    mouseEvents(arena, point, 1, 1, events);
                    // UTF-16 chunks retain surrogate pairs and keep each native event bounded.
                    for (String chunk : unicodeChunks(action.text(), 64)) keyEvents(0, 0, chunk, arena, events);
                }
                case SCROLL -> events.add((MemorySegment) api.call("CGEventCreateScrollWheelEvent2", ValueLayout.ADDRESS,
                        new MemoryLayout[]{ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
                                ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT},
                        MemorySegment.NULL, 0, 1, -action.amount() * 40, 0, 0));
            }
            if (events.isEmpty() || events.stream().anyMatch(MacNative::nil))
                return result(FAILED, action, "System event preparation failed before dispatch");
            if (action.kind() == DesktopAction.Kind.SCROLL) {
                MemorySegment location = arena.allocate(MacNative.POINT);
                location.set(ValueLayout.JAVA_DOUBLE, 0, point[0]);
                location.set(ValueLayout.JAVA_DOUBLE, 8, point[1]);
                api.call("CGEventSetLocation", null, new MemoryLayout[]{ValueLayout.ADDRESS, MacNative.POINT},
                        events.getFirst(), location);
            }
            MacEventSequence.Outcome outcome = MacEventSequence.deliver(events,
                    action.kind() != DesktopAction.Kind.SCROLL,
                    () -> ready(target, null),
                    index -> (!pointerRequired(action.kind(), index) || ready(target, point))
                            && (action.kind() != DesktopAction.Kind.CLICK || action.clicks() != 2
                                    || index != 2 || secondClickUnchanged.getAsBoolean()),
                    event -> api.call("CGEventPost", null,
                            new MemoryLayout[]{ValueLayout.JAVA_INT, ValueLayout.ADDRESS}, 0, event),
                    index -> pause(eventDelayMillis(action.kind(), index)));
            return switch (outcome) {
                case ACCEPTED -> result(ACCEPTED, action,
                        "System input posted; application effect requires a fresh observation");
                case NOT_SENT -> result(DENIED, action, "Foreground ownership changed before system input");
                case FAILED_BEFORE_POST -> result(FAILED, action, "System input preparation failed before posting");
                case UNKNOWN -> result(UNKNOWN, action, "System input may be partially applied; reconcile before continuing");
            };
        } catch (RuntimeException | LinkageError failure) {
            return result(FAILED, action, "System event preparation failed before posting");
        } finally { events.forEach(api::release); }
    }

    private void mouseEvents(Arena arena, double[] point, DesktopAction action, List<MemorySegment> events) {
        mouseEvents(arena, point, action.button(), action.clicks(), events);
    }

    private void mouseEvents(Arena arena, double[] point, int selectedButton, int clicks,
            List<MemorySegment> events) {
        MemorySegment location = arena.allocate(MacNative.POINT);
        location.set(ValueLayout.JAVA_DOUBLE, 0, point[0]);
        location.set(ValueLayout.JAVA_DOUBLE, 8, point[1]);
        int button = cgButton(selectedButton);
        int down = button == 0 ? 1 : button == 1 ? 3 : 25;
        int up = button == 0 ? 2 : button == 1 ? 4 : 26;
        for (int click = 1; click <= clicks; click++) {
            for (int type : new int[]{down, up}) {
                MemorySegment event = (MemorySegment) api.call("CGEventCreateMouseEvent", ValueLayout.ADDRESS,
                        new MemoryLayout[]{ValueLayout.ADDRESS, ValueLayout.JAVA_INT, MacNative.POINT,
                                ValueLayout.JAVA_INT}, MemorySegment.NULL, type, location, button);
                if (!MacNative.nil(event)) api.call("CGEventSetIntegerValueField", null,
                        new MemoryLayout[]{ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG}, event, 1, (long) click);
                events.add(event);
            }
        }
    }

    private void keyEvents(int code, long flags, String text, Arena arena, List<MemorySegment> events) {
        for (byte down : new byte[]{1, 0}) {
            MemorySegment event = (MemorySegment) api.call("CGEventCreateKeyboardEvent", ValueLayout.ADDRESS,
                    new MemoryLayout[]{ValueLayout.ADDRESS, ValueLayout.JAVA_SHORT, ValueLayout.JAVA_BYTE},
                    MemorySegment.NULL, (short) code, down);
            if (!MacNative.nil(event)) {
                api.call("CGEventSetFlags", null,
                        new MemoryLayout[]{ValueLayout.ADDRESS, ValueLayout.JAVA_LONG}, event, flags);
                if (!text.isEmpty()) {
                    MemorySegment chars = arena.allocateFrom(ValueLayout.JAVA_CHAR, text.toCharArray());
                    api.call("CGEventKeyboardSetUnicodeString", null,
                            new MemoryLayout[]{ValueLayout.ADDRESS, ValueLayout.JAVA_LONG, ValueLayout.ADDRESS},
                            event, (long) text.length(), chars);
                }
            }
            events.add(event);
        }
    }

    boolean ready(NativeWindow target, double[] pointer) {
        if (activePid() != target.processId() || windows.instance(target.processId()) != target.processInstanceId()
                || !windows.geometryCurrent(target) || !ax.focused(target) || windows.frontmostWindow(target.processId()) != target.windowId()) return false;
        if (pointer == null) return true;
        MemorySegment infos = windows.cgWindows(1, 0);
        try {
            List<PointerSurface> surfaces = new ArrayList<>();
            for (MemorySegment info : api.array(infos, 4096)) {
                double[] bounds = windows.dictionaryBounds(info);
                if (bounds == null || bounds[2] <= 0 || bounds[3] <= 0
                        || windows.dictionaryNumber(info, "kCGWindowLayer") < 0) continue;
                MemorySegment alphaKey = api.symbol("kCGWindowAlpha").reinterpret(8).get(ValueLayout.ADDRESS, 0);
                MemorySegment alpha = api.object(info, "objectForKey:", alphaKey);
                if (!MacNative.nil(alpha) && (double) api.message(alpha, "doubleValue", ValueLayout.JAVA_DOUBLE,
                        new MemoryLayout[0]) <= 0.01) continue;
                surfaces.add(new PointerSurface(info, bounds));
            }
            int hit = firstHitIndex(surfaces.stream().map(PointerSurface::bounds).toList(), pointer);
            if (hit < 0) return false;
            PointerSurface surface = surfaces.get(hit);
            if (windows.dictionaryNumber(surface.info(), "kCGWindowNumber") == target.windowId()
                    && windows.dictionaryNumber(surface.info(), "kCGWindowOwnerPID") == target.processId()
                    && MacAccessibility.sameBounds(surface.bounds(), target)) return true;
            // Only the actual topmost hit may use the documented AppKit Dock exception.
            return dockOverlayResolves(surface.info(), surface.bounds(), pointer, target);
        } finally { api.release(infos); }
    }

    private boolean dockOverlayResolves(MemorySegment info, double[] bounds, double[] point, NativeWindow target) {
        if (windows.dictionaryNumber(info, "kCGWindowLayer") != 20
                || windows.dictionaryNumber(info, "kCGWindowSharingState") != 1
                || windows.dictionaryText(info, "kCGWindowName").isBlank()) return false;
        long pid = windows.dictionaryNumber(info, "kCGWindowOwnerPID");
        MemorySegment owner = api.object(api.cls("NSRunningApplication"),
                "runningApplicationWithProcessIdentifier:", (int) pid);
        if (!api.text(api.object(owner, "bundleIdentifier")).equals("com.apple.dock")) return false;
        int display = (int) api.call("CGMainDisplayID", ValueLayout.JAVA_INT, new MemoryLayout[0]);
        double[] primary = api.functionRect("CGDisplayBounds", new MemoryLayout[]{ValueLayout.JAVA_INT}, display);
        if (primary[0] != 0 || primary[1] != 0 || primary[2] <= 0 || primary[3] <= 0
                || !java.util.Arrays.equals(primary, bounds)) return false;
        long receiver = MacMainThread.query(api, () -> {
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment cocoaPoint = arena.allocate(MacNative.POINT);
                cocoaPoint.set(ValueLayout.JAVA_DOUBLE, 0, point[0]);
                cocoaPoint.set(ValueLayout.JAVA_DOUBLE, 8, primary[3] - point[1]);
                return (long) api.message(api.cls("NSWindow"), "windowNumberAtPoint:belowWindowWithWindowNumber:",
                        ValueLayout.JAVA_LONG, new MemoryLayout[]{MacNative.POINT, ValueLayout.JAVA_LONG},
                        cocoaPoint, 0L);
            }
        });
        return receiver > 0 && receiver == target.windowId() && activePid() == target.processId()
                && windows.geometryCurrent(target) && ax.focused(target);
    }

    static boolean pointerRequired(DesktopAction.Kind kind, int eventIndex) {
        return kind == DesktopAction.Kind.CLICK || kind == DesktopAction.Kind.SCROLL
                || kind == DesktopAction.Kind.TYPE && eventIndex < 2;
    }

    static long eventDelayMillis(DesktopAction.Kind kind, int postedIndex) {
        return kind == DesktopAction.Kind.TYPE && postedIndex == 1 ? 50 : 10;
    }

    static int firstHitIndex(List<double[]> windows, double[] point) {
        if (point == null || point.length != 2 || !Double.isFinite(point[0]) || !Double.isFinite(point[1])) return -1;
        for (int i = 0; i < windows.size(); i++) {
            double[] bounds = windows.get(i);
            if (bounds == null || bounds.length != 4 || bounds[2] <= 0 || bounds[3] <= 0) continue;
            boolean finite = true;
            for (double value : bounds) if (!Double.isFinite(value)) finite = false;
            if (finite && point[0] >= bounds[0] && point[1] >= bounds[1]
                    && point[0] < bounds[0] + bounds[2] && point[1] < bounds[1] + bounds[3]) return i;
        }
        return -1;
    }

    private record PointerSurface(MemorySegment info, double[] bounds) { }

    static int cgButton(int button) {
        return switch (button) {
            case 1 -> 0;
            case 2 -> 2; // API middle => CG center
            case 3 -> 1; // API right => CG right
            default -> throw new IllegalArgumentException("Mouse button must be 1-3");
        };
    }

    static Key parseKey(String text) {
        String[] parts = text.strip().toUpperCase(Locale.ROOT).split("\\+", -1);
        if (parts.length == 0) return null;
        long flags = 0;
        for (int i = 0; i < parts.length - 1; i++) {
            long flag = switch (parts[i].strip()) {
                case "CTRL", "CONTROL" -> 1L << 18;
                case "SHIFT" -> 1L << 17;
                case "ALT", "OPTION" -> 1L << 19;
                case "CMD", "COMMAND", "META" -> 1L << 20;
                default -> 0;
            };
            if (flag == 0) return null;
            flags |= flag;
        }
        Integer code = KEYS.get(parts[parts.length - 1].strip());
        return code == null ? null : new Key(code, flags);
    }

    static List<String> unicodeChunks(String text, int maxUnits) {
        if (maxUnits < 2) throw new IllegalArgumentException("Unicode chunk size must include a surrogate pair");
        List<String> chunks = new ArrayList<>();
        for (int start = 0; start < text.length();) {
            int end = Math.min(text.length(), start + maxUnits);
            if (end < text.length() && Character.isHighSurrogate(text.charAt(end - 1))) end--;
            chunks.add(text.substring(start, end)); start = end;
        }
        return List.copyOf(chunks);
    }

    private static void pause(long millis) {
        try { Thread.sleep(millis); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IllegalStateException("Input interrupted", interrupted); }
    }

    private DesktopActionResult result(DesktopActionResult.Status status, DesktopAction action, String detail) {
        return new DesktopActionResult(status, detail, action.windowGeneration()).withContext(
                DesktopActionResult.Mode.FOREGROUND_SYNTHETIC, action.observationId(),
                status == UNKNOWN ? DesktopActionResult.NextStep.RECONCILE : DesktopActionResult.NextStep.OBSERVE);
    }
    record Key(int code, long flags) { }
}
