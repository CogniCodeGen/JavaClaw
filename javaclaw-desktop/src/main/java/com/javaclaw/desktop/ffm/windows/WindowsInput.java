package com.javaclaw.desktop.ffm.windows;

import com.javaclaw.desktop.api.DesktopAction;
import com.javaclaw.desktop.api.DesktopActionResult;
import java.lang.foreign.Arena;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.function.BooleanSupplier;
import static com.javaclaw.desktop.ffm.windows.Win32.*;

/** Explicit foreground lease and bounded SendInput batches; never redirects messages to hidden controls. */
final class WindowsInput {
    private final WindowsWindows windows;
    private final Win32 win;
    private long target, previous;
    private long previousPid, previousInstance;
    private Point previousCursor, syntheticCursor;
    private boolean moved;
    WindowsInput(WindowsWindows windows) { this.windows = windows; this.win = windows.win; }

    void prepare(long hwnd) {
        if (target == hwnd && windows.foreground() == hwnd) return;
        if (target != 0 && windows.foreground() != target) {
            target = 0;
            throw new SecurityException("Foreground changed outside the input lease; observe before taking control again");
        }
        restore();
        previous = windows.foreground();
        previousPid = previous == 0 ? 0 : windows.pid(previous);
        previousInstance = previousPid == 0 ? 0 : windows.instance(previousPid);
        previousCursor = cursor();
        if (windows.foreground() != hwnd)
            win.integer("user32", "SetForegroundWindow", new MemoryLayout[]{P}, handle(hwnd));
        if (windows.foreground() != hwnd || !windows.visible(hwnd) || windows.iconic(hwnd)) {
            target = 0;
            throw new SecurityException("Windows refused to activate the exact target window");
        }
        target = hwnd;
    }

    void restore() {
        if (target == 0) return;
        long leased = target;
        target = 0;
        if (windows.foreground() != leased) return;
        if (moved && previousCursor != null && syntheticCursor != null && syntheticCursor.equals(cursor()))
            win.integer("user32", "SetCursorPos", new MemoryLayout[]{I, I}, previousCursor.x(), previousCursor.y());
        moved = false;
        if (previous != 0 && previous != leased && windows.valid(previousPid, previous, previousInstance)
                && windows.visible(previous) && !windows.iconic(previous))
            win.integer("user32", "SetForegroundWindow", new MemoryLayout[]{P}, handle(previous));
    }

    DesktopActionResult perform(long hwnd, WindowsWindows.Rect bounds, int frameWidth, int frameHeight,
            DesktopAction action, BooleanSupplier ready, BooleanSupplier clickPixelsCurrent) {
        boolean[] attempted = {false};
        try { return perform(hwnd, bounds, frameWidth, frameHeight, action, ready, clickPixelsCurrent, attempted); }
        catch (RuntimeException | LinkageError failure) {
            return result(action, attempted[0] ? DesktopActionResult.Status.UNKNOWN : DesktopActionResult.Status.FAILED,
                    attempted[0] ? "Foreground input completion is uncertain; do not retry" : "Foreground target could not be validated",
                    attempted[0]);
        }
    }

    private DesktopActionResult perform(long hwnd, WindowsWindows.Rect bounds, int frameWidth, int frameHeight,
            DesktopAction action, BooleanSupplier ready, BooleanSupplier clickPixelsCurrent, boolean[] attempted) {
        if (target != hwnd || windows.foreground() != hwnd || !ready.getAsBoolean())
            return result(action, DesktopActionResult.Status.DENIED, "Exact target has no current foreground lease", false);
        if (heldModifiers()) return result(action, DesktopActionResult.Status.DENIED, "User is holding a modifier or mouse button; input was not sent", false);
        if (action.kind() == DesktopAction.Kind.TYPE && action.textOperation() != DesktopAction.TextOperation.INSERT_TEXT)
            return result(action, DesktopActionResult.Status.UNSUPPORTED, "Foreground typing inserts text; use the observed value control for SET_TEXT", false);
        List<Event> events = new ArrayList<>();
        boolean pointerDispatched = false;
        Point point = new Point(bounds.left() + (int) ((long) action.x() * bounds.width() / frameWidth),
                bounds.top() + (int) ((long) action.y() * bounds.height() / frameHeight));
        if (action.kind() == DesktopAction.Kind.KEY) {
            List<Integer> chord = chord(action.text());
            if (chord.isEmpty()) return result(action, DesktopActionResult.Status.UNSUPPORTED, "Unsupported key chord", false);
            if (!focused(hwnd)) return result(action, DesktopActionResult.Status.DENIED, "Focused control is outside the target window", false);
            for (int key : chord) events.add(Event.key(key, 0, 0));
            for (int index = chord.size() - 1; index >= 0; index--) events.add(Event.key(chord.get(index), 0, 2));
        } else if (action.kind() == DesktopAction.Kind.TYPE) {
            if (!validUnicode(action.text())) return result(action, DesktopActionResult.Status.UNSUPPORTED, "Text contains NUL or malformed UTF-16", false);
            if (action.x() >= 0 && action.y() >= 0) {
                DesktopActionResult focus = perform(hwnd, bounds, frameWidth, frameHeight, focusClick(action), ready, clickPixelsCurrent, attempted);
                if (focus.status() != DesktopActionResult.Status.ACCEPTED && focus.status() != DesktopActionResult.Status.VERIFIED)
                    return result(action, focus.status(), "Text focus click did not complete: " + focus.detail(), focus.dispatchAttempted());
                pointerDispatched = true;
            }
            if (!focused(hwnd)) return result(action, pointerDispatched ? DesktopActionResult.Status.UNKNOWN : DesktopActionResult.Status.DENIED,
                    "Focused control is outside the target window", pointerDispatched);
            for (char unit : action.text().toCharArray()) {
                events.add(Event.key(0, unit, 4)); events.add(Event.key(0, unit, 6));
            }
        } else {
            if (!bounds.contains(point.x(), point.y()) || !pointHits(hwnd, point))
                return result(action, DesktopActionResult.Status.DENIED, "Frame point does not hit the exact target window", false);
            Event move = movement(point);
            if (move == null) return result(action, DesktopActionResult.Status.DENIED, "Virtual desktop geometry is unavailable", false);
            if (action.kind() == DesktopAction.Kind.CLICK) {
                if (!ready.getAsBoolean() || windows.foreground() != hwnd || !pointHits(hwnd, point))
                    return result(action, DesktopActionResult.Status.DENIED, "Target changed before pointer movement", false);
                int inserted;
                try { attempted[0] = true; inserted = send(List.of(move)); }
                catch (RuntimeException | LinkageError uncertain) {
                    return result(action, DesktopActionResult.Status.UNKNOWN, "Pointer movement completion is uncertain", true);
                }
                pointerDispatched = true;
                moved = inserted > 0;
                syntheticCursor = cursor();
                if (inserted != 1 || !ready.getAsBoolean() || !pointHits(hwnd, point) || !clickPixelsCurrent.getAsBoolean())
                    return result(action, DesktopActionResult.Status.UNKNOWN, "Target or captured click region changed after pointer movement; no press was sent", true);
            } else events.add(move);
            if (action.kind() == DesktopAction.Kind.CLICK) {
                int down = action.button() == 1 ? 2 : action.button() == 2 ? 0x20 : 8;
                int up = action.button() == 1 ? 4 : action.button() == 2 ? 0x40 : 0x10;
                events.add(Event.mouse(down, 0, 0, 0)); events.add(Event.mouse(up, 0, 0, 0));
            } else events.add(Event.mouse(0x800, -action.amount() * 120, 0, 0));
        }
        if (!ready.getAsBoolean() || windows.foreground() != hwnd
                || ((action.kind() == DesktopAction.Kind.CLICK || action.kind() == DesktopAction.Kind.SCROLL)
                ? !pointHits(hwnd, point) : !focused(hwnd)))
            return result(action, pointerDispatched ? DesktopActionResult.Status.UNKNOWN : DesktopActionResult.Status.DENIED,
                    "Target changed before SendInput", pointerDispatched);
        int admitted = 0;
        int batchStart = 0;
        while (batchStart < events.size()) {
            int batchEnd = action.kind() == DesktopAction.Kind.TYPE ? Math.min(events.size(), batchStart + 128) : events.size();
            if (batchEnd < events.size() && Character.isHighSurrogate((char) events.get(batchEnd - 2).x())) batchEnd -= 2;
            if (!ready.getAsBoolean() || windows.foreground() != hwnd
                    || (action.kind() == DesktopAction.Kind.TYPE && (!focused(hwnd) || heldModifiers())))
                return result(action, admitted == 0 && !pointerDispatched ? DesktopActionResult.Status.DENIED : DesktopActionResult.Status.UNKNOWN,
                        "Target or focused control changed before the next input batch", admitted > 0 || pointerDispatched);
            List<Event> batch = events.subList(batchStart, batchEnd);
            int inserted;
            try { attempted[0] = true; inserted = send(batch); }
            catch (RuntimeException | LinkageError uncertain) {
                return result(action, DesktopActionResult.Status.UNKNOWN, "Native SendInput completion is unknown; do not retry", true);
            }
            admitted += Math.max(0, inserted);
            if (inserted > 0 && (action.kind() == DesktopAction.Kind.CLICK || action.kind() == DesktopAction.Kind.SCROLL)) {
                moved = true;
                syntheticCursor = cursor();
            }
            if (inserted != batch.size()) {
                releasePartialInput(batch, inserted);
                return result(action, DesktopActionResult.Status.UNKNOWN, "SendInput did not confirm the entire batch; do not retry", true);
            }
            batchStart = batchEnd;
        }
        if (action.kind() == DesktopAction.Kind.CLICK || action.kind() == DesktopAction.Kind.SCROLL) {
            moved |= admitted > 0;
            syntheticCursor = cursor();
        }
        if (!ready.getAsBoolean() || windows.foreground() != hwnd
                || (action.kind() == DesktopAction.Kind.TYPE || action.kind() == DesktopAction.Kind.KEY) && !focused(hwnd))
            return result(action, DesktopActionResult.Status.UNKNOWN, "Target changed after input admission; reconcile the effect", true);
        if (action.kind() == DesktopAction.Kind.CLICK && action.clicks() == 2) {
            // Keep each click's target check separate: an opening popup must never receive the second click.
            if (!pointHits(hwnd, point) || !ready.getAsBoolean() || !clickPixelsCurrent.getAsBoolean())
                return result(action, DesktopActionResult.Status.UNKNOWN, "Target changed after the first click; double click stopped", true);
            int down = action.button() == 1 ? 2 : action.button() == 2 ? 0x20 : 8;
            int up = action.button() == 1 ? 4 : action.button() == 2 ? 0x40 : 0x10;
            List<Event> second = List.of(Event.mouse(down, 0, 0, 0), Event.mouse(up, 0, 0, 0));
            attempted[0] = true;
            int secondAdmitted = send(second);
            if (secondAdmitted != 2) releasePartialInput(second, secondAdmitted);
            if (secondAdmitted != 2 || !ready.getAsBoolean())
                return result(action, DesktopActionResult.Status.UNKNOWN, "Double click delivery was incomplete or target changed", true);
        }
        return result(action, DesktopActionResult.Status.ACCEPTED, "Windows accepted foreground input; application outcome requires observation", true);
    }

    private int send(List<Event> events) {
        if (events.isEmpty()) return 0;
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment input = arena.allocate(events.size() * 40L, 8);
            for (int index = 0; index < events.size(); index++) encode(input.asSlice(index * 40L, 40), events.get(index));
            return win.integer("user32", "SendInput", new MemoryLayout[]{I, P, I}, events.size(), input, 40);
        }
    }

    static void encode(MemorySegment input, Event event) {
        input.fill((byte) 0);
        input.set(I, 0, event.type());
        if (event.type() == 0) {
            input.set(I, 8, event.x()); input.set(I, 12, event.y());
            input.set(I, 16, event.data()); input.set(I, 20, event.flags());
        } else {
            input.set(ValueLayout.JAVA_SHORT, 8, (short) event.data());
            input.set(ValueLayout.JAVA_SHORT, 10, (short) event.x());
            input.set(I, 12, event.flags());
        }
    }

    private void releasePartialInput(List<Event> events, int admitted) {
        if (admitted < 1) return;
        List<Event> sent = events.subList(0, Math.min(admitted, events.size()));
        List<Event> release = new ArrayList<>(sent.stream()
                .filter(event -> event.type() == 1 && (event.flags() & 2) == 0)
                .map(event -> Event.key(event.data(), event.x(), event.flags() | 2)).toList());
        for (Event event : sent) {
            if (event.type() != 0) continue;
            int up = (event.flags() & 2) != 0 ? 4 : (event.flags() & 8) != 0 ? 0x10 : (event.flags() & 0x20) != 0 ? 0x40 : 0;
            if (up != 0) release.add(Event.mouse(up, 0, 0, 0));
        }
        if (!release.isEmpty()) {
            try { send(release); } // Only release cleanup; never replay a press or command.
            catch (RuntimeException | LinkageError ignored) { /* The caller already returns UNKNOWN. */ }
        }
    }

    private Event movement(Point point) {
        int left = metric(76), top = metric(77), width = metric(78), height = metric(79);
        if (width < 2 || height < 2) return null;
        int x = (int) Math.clamp(((long) point.x() - left) * 65535 / (width - 1), 0, 65535);
        int y = (int) Math.clamp(((long) point.y() - top) * 65535 / (height - 1), 0, 65535);
        return Event.mouse(1 | 0x8000 | 0x4000, 0, x, y);
    }

    private int metric(int code) { return win.integer("user32", "GetSystemMetrics", new MemoryLayout[]{I}, code); }
    private Point cursor() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment point = arena.allocate(8, 4);
            return win.integer("user32", "GetCursorPos", new MemoryLayout[]{P}, point) == 0 ? null
                    : new Point(point.get(I, 0), point.get(I, 4));
        }
    }

    private boolean pointHits(long hwnd, Point point) {
        long packed = Integer.toUnsignedLong(point.x()) | ((long) point.y() << 32);
        MemorySegment hit = win.pointer("user32", "WindowFromPoint", new MemoryLayout[]{L}, packed);
        return !nullPointer(hit) && win.pointer("user32", "GetAncestor", new MemoryLayout[]{P, I}, hit, 2).address() == hwnd;
    }

    private boolean focused(long hwnd) {
        try (Arena arena = Arena.ofConfined()) {
            int thread = win.integer("user32", "GetWindowThreadProcessId", new MemoryLayout[]{P, P}, handle(hwnd), MemorySegment.NULL);
            MemorySegment info = arena.allocate(72, 8); // GUITHREADINFO, six HWNDs and RECT.
            info.set(I, 0, 72);
            if (win.integer("user32", "GetGUIThreadInfo", new MemoryLayout[]{I, P}, thread, info) == 0) return false;
            MemorySegment focus = info.get(P, 16);
            if (nullPointer(focus)) focus = info.get(P, 8);
            return !nullPointer(focus) && win.pointer("user32", "GetAncestor", new MemoryLayout[]{P, I}, focus, 2).address() == hwnd;
        }
    }

    private boolean heldModifiers() {
        for (int key : new int[]{0x10, 0x11, 0x12, 0x5b, 0x5c, 1, 2, 4}) {
            Object value = invoke(win.function("user32", "GetAsyncKeyState", ValueLayout.JAVA_SHORT, I), key);
            if ((((short) value) & 0x8000) != 0) return true;
        }
        return false;
    }

    static List<Integer> chord(String text) {
        if (text == null || text.isBlank() || text.length() > 64) return List.of();
        List<Integer> result = new ArrayList<>();
        HashSet<Integer> seen = new HashSet<>();
        String[] tokens = text.toUpperCase(Locale.ROOT).split("\\+", -1);
        for (int index = 0; index < tokens.length; index++) {
            int modifier = switch (tokens[index]) { case "CTRL", "CONTROL" -> 0x11; case "SHIFT" -> 0x10;
                case "ALT" -> 0x12; case "WIN", "META" -> 0x5b; default -> 0; };
            int key = modifier == 0 ? named(tokens[index]) : modifier;
            if (key == 0 || !seen.add(key) || modifier != 0 && index == tokens.length - 1
                    || modifier == 0 && index != tokens.length - 1) return List.of();
            result.add(key);
        }
        return List.copyOf(result);
    }

    private static int named(String name) {
        if (name.length() == 1 && (name.charAt(0) >= 'A' && name.charAt(0) <= 'Z'
                || name.charAt(0) >= '0' && name.charAt(0) <= '9')) return name.charAt(0);
        if (name.matches("F(?:[1-9]|1[0-2])")) return 0x70 + Integer.parseInt(name.substring(1)) - 1;
        return switch (name) { case "ENTER", "RETURN" -> 13; case "TAB" -> 9; case "ESC", "ESCAPE" -> 27;
            case "BACKSPACE" -> 8; case "DELETE" -> 46; case "SPACE" -> 32; case "UP" -> 38; case "DOWN" -> 40;
            case "LEFT" -> 37; case "RIGHT" -> 39; case "HOME" -> 36; case "END" -> 35; case "PAGEUP" -> 33;
            case "PAGEDOWN" -> 34; default -> 0; };
    }

    private static DesktopActionResult result(DesktopAction action, DesktopActionResult.Status status, String detail, boolean dispatched) {
        return new DesktopActionResult(status, detail, action.windowGeneration(), DesktopActionResult.Mode.FOREGROUND_SYNTHETIC,
                status == DesktopActionResult.Status.UNKNOWN ? DesktopActionResult.Reason.DELIVERY_UNCERTAIN
                : status == DesktopActionResult.Status.DENIED ? DesktopActionResult.Reason.ACCESS_DENIED
                : status == DesktopActionResult.Status.UNSUPPORTED ? DesktopActionResult.Reason.UNSUPPORTED_ACTION
                : status == DesktopActionResult.Status.FAILED ? DesktopActionResult.Reason.PLATFORM_FAILURE : DesktopActionResult.Reason.NONE,
                dispatched, action.observationId(), status == DesktopActionResult.Status.UNKNOWN ? DesktopActionResult.NextStep.RECONCILE
                : dispatched ? DesktopActionResult.NextStep.OBSERVE : DesktopActionResult.NextStep.NONE);
    }

    static boolean validUnicode(String text) {
        for (int index = 0; index < text.length(); index++) {
            char unit = text.charAt(index);
            if (unit == 0 || Character.isLowSurrogate(unit)) return false;
            if (Character.isHighSurrogate(unit) && (++index >= text.length() || !Character.isLowSurrogate(text.charAt(index)))) return false;
        }
        return true;
    }

    static DesktopAction focusClick(DesktopAction action) {
        return new DesktopAction(DesktopAction.Kind.CLICK, action.x(), action.y(), 1, 1, 0, "",
                action.windowGeneration(), action.observationId(), action.elementId(), action.contentRevision());
    }

    record Event(int type, int flags, int data, int x, int y) {
        static Event mouse(int flags, int data, int x, int y) { return new Event(0, flags, data, x, y); }
        static Event key(int vk, int scan, int flags) { return new Event(1, flags, vk, scan, 0); }
    }
    private record Point(int x, int y) { }
}
