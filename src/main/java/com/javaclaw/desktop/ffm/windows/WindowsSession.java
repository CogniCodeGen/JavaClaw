package com.javaclaw.desktop.ffm.windows;

import com.javaclaw.desktop.api.DesktopAction;
import com.javaclaw.desktop.api.DesktopActionResult;
import com.javaclaw.desktop.api.DesktopElement;
import com.javaclaw.desktop.api.DesktopFrame;
import com.javaclaw.desktop.api.DesktopFrameGeometry;
import com.javaclaw.desktop.ffm.SystemDesktopSession;
import com.javaclaw.desktop.nativebridge.DesktopBridge.NativeWindow;
import com.javaclaw.desktop.spi.DesktopClickGuard;
import java.lang.foreign.MemoryLayout;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Objects;

/** Java-owned session state; a destroyed root or reused PID/handle never rebinds. */
final class WindowsSession implements SystemDesktopSession {
    private final WindowsWindows windows;
    private final NativeWindow root;
    private final WindowsCapture capture;
    private final WindowsInput input;
    private final WindowsUia uia;
    private NativeWindow current;
    private DesktopFrame frame;
    private byte[] previousPixels;
    private long generation = 1, revision;
    private boolean closed, destroyed;

    WindowsSession(WindowsWindows windows, NativeWindow root) {
        this.windows = windows;
        this.root = root;
        current = windows.describe(root.processId(), root.windowId(), root.processInstanceId());
        capture = new WindowsCapture(windows.win);
        input = new WindowsInput(windows);
        uia = new WindowsUia(windows.win);
        capture.bind(root.windowId(), root.width(), root.height());
    }

    @Override public synchronized NativeWindow current() { refresh(); return current; }

    private void refresh() {
        if (closed || destroyed || capture.targetClosed() || !windows.valid(root.processId(), root.windowId(), root.processInstanceId())) {
            destroyed = true;
            frame = null;
            uia.clear();
            throw new IllegalStateException("Bound Windows process or root window is no longer available");
        }
        long hwnd = root.windowId();
        NativeWindow next = windows.describe(root.processId(), hwnd, root.processInstanceId());
        if (current.windowId() != next.windowId() || current.x() != next.x() || current.y() != next.y()
                || current.width() != next.width() || current.height() != next.height()
                || ((current.flags() ^ next.flags()) & 3) != 0) {
            generation++;
            frame = null;
            previousPixels = null;
            uia.clear();
        }
        current = next;
    }

    @Override public synchronized Optional<DesktopFrame> capture(String targetId, int timeoutMillis) {
        refresh();
        if ((current.flags() & 1) != 0 || (current.flags() & 2) == 0) return Optional.empty();
        NativeWindow before = current;
        long expectedGeneration = generation;
        Optional<WindowsCapture.Pixels> pixels = capture.capture(before.windowId(), before.width(), before.height(), timeoutMillis);
        refresh();
        if (pixels.isEmpty() || expectedGeneration != generation || before.windowId() != current.windowId()) return Optional.empty();
        WindowsCapture.Pixels value = pixels.orElseThrow();
        if (!Arrays.equals(previousPixels, value.bgra())) revision++;
        previousPixels = value.bgra();
        if (revision < 1) revision = 1;
        int dpi = windows.win.integer("user32", "GetDpiForWindow", new MemoryLayout[]{Win32.P}, Win32.handle(before.windowId()));
        double logicalScale = dpi > 0 ? 96d / dpi : 1;
        frame = new DesktopFrame(targetId, generation, value.capturedAtMillis(), value.width(), value.height(), value.width() * 4,
                value.bgra(), revision, new DesktopFrameGeometry(before.width() * logicalScale, before.height() * logicalScale, 0, 0,
                value.width(), value.height(), false));
        return Optional.of(frame);
    }

    @Override public synchronized List<DesktopElement> elements(DesktopFrame observed) {
        refresh();
        if (frame == null || observed.windowGeneration() != generation || observed.contentRevision() != frame.contentRevision()
                || observed.capturedAtMillis() != frame.capturedAtMillis()) return List.of();
        return uia.elements(current.windowId(), rect(current), observed);
    }

    @Override public synchronized String diagnostics() { return capture.diagnostics() + "; " + uia.diagnostics(); }

    @Override public synchronized Optional<Boolean> targetActive() {
        try {
            refresh();
            long foreground = windows.foreground();
            long pid = foreground == 0 ? 0 : windows.pid(foreground);
            return pid == 0 ? Optional.empty() : Optional.of(pid == root.processId());
        }
        catch (RuntimeException unavailable) { return Optional.empty(); }
    }

    @Override public synchronized void prepareForeground() {
        refresh();
        if ((current.flags() & 1) != 0 || (current.flags() & 2) == 0)
            throw new SecurityException("Minimized or hidden windows cannot receive foreground input");
        input.prepare(current.windowId());
        // Foreground preparation invalidates the previous observation even when the HWND stays bound.
        refresh();
        frame = null;
        uia.clear();
    }

    @Override public synchronized void restoreForeground() { input.restore(); }

    @Override public synchronized DesktopActionResult perform(DesktopAction action, boolean foreground) {
        try { refresh(); }
        catch (RuntimeException unavailable) { return stale(action, "Bound window is no longer available"); }
        if (!validFrame(action)) return stale(action, "Window generation, capture revision, point, or observation is stale");
        if ((current.flags() & 1) != 0 || (current.flags() & 2) == 0)
            return denied(action, "Target is minimized or hidden");
        if (foreground && action.kind() != DesktopAction.Kind.CLICK) {
            DesktopFrame observed = frame;
            DesktopFrame fresh;
            try { fresh = capture(observed.targetId(), 1000).orElse(null); }
            catch (SecurityException denied) { return denied(action, "Fresh pre-input capture was denied; no input was sent"); }
            catch (RuntimeException unavailable) { return stale(action, "Fresh pre-input frame is unavailable; no input was sent"); }
            if (!sameObservation(observed, fresh, action, System.currentTimeMillis()))
                return stale(action, "Captured content changed or a fresh frame is unavailable; no input was sent");
        }
        long hwnd = current.windowId();
        long expectedGeneration = generation;
        var ready = (java.util.function.BooleanSupplier) () -> ready(hwnd, expectedGeneration, foreground);
        DesktopAction guardedPress = action.kind() == DesktopAction.Kind.TYPE ? WindowsInput.focusClick(action) : action;
        DesktopClickGuard clickGuard = foreground && (action.kind() == DesktopAction.Kind.CLICK
                || action.kind() == DesktopAction.Kind.TYPE && action.x() >= 0 && action.y() >= 0)
                ? DesktopClickGuard.capture(frame, Math.max(0, action.x() - 15), Math.max(0, action.y() - 15),
                Math.min(frame.width() - Math.max(0, action.x() - 15), 31),
                Math.min(frame.height() - Math.max(0, action.y() - 15), 31)) : null;
        String targetId = frame.targetId();
        var pixelsCurrent = (java.util.function.BooleanSupplier) () -> {
            try {
                return clickGuard == null || clickGuard.matches(guardedPress,
                        capture(targetId, 100).orElse(null), System.currentTimeMillis());
            } catch (RuntimeException unavailable) { return false; }
        };
        DesktopFrame admittedFrame = frame;
        var semanticFresh = (java.util.function.BooleanSupplier) () -> {
            if (action.kind() != DesktopAction.Kind.TYPE && action.kind() != DesktopAction.Kind.SCROLL) return true;
            try { return sameObservation(admittedFrame, capture(targetId, 1000).orElse(null), action, System.currentTimeMillis()); }
            catch (RuntimeException unavailable) { return false; }
        };
        DesktopActionResult result = foreground ? input.perform(hwnd, rect(current), frame.width(), frame.height(), action, ready, pixelsCurrent)
                : uia.perform(hwnd, rect(current), frame, action, ready, semanticFresh);
        if (result.dispatchAttempted()) {
            frame = null;
            uia.clear();
        }
        return result;
    }

    private boolean ready(long hwnd, long expectedGeneration, boolean foreground) {
        try {
            refresh();
            if (generation != expectedGeneration || current.windowId() != hwnd || (current.flags() & 3) != 2) return false;
            long active = windows.foreground();
            long activePid = active == 0 ? 0 : windows.pid(active);
            return inputWindowAllowed(root.processId(), hwnd, active, activePid, foreground);
        } catch (RuntimeException unavailable) { return false; }
    }

    static boolean inputWindowAllowed(long targetPid, long targetWindow, long activeWindow, long activePid, boolean foreground) {
        if (targetPid <= 0 || targetWindow == 0 || activeWindow == 0 || activePid <= 0) return false;
        return foreground ? activeWindow == targetWindow && activePid == targetPid : activePid != targetPid;
    }

    static boolean sameObservation(DesktopFrame observed, DesktopFrame fresh, DesktopAction action, long nowMillis) {
        return observed != null && fresh != null && observed.targetId().equals(fresh.targetId())
                && observed.windowGeneration() == action.windowGeneration() && fresh.windowGeneration() == action.windowGeneration()
                && observed.contentRevision() == action.contentRevision() && fresh.contentRevision() == action.contentRevision()
                && fresh.capturedAtMillis() >= observed.capturedAtMillis() && fresh.capturedAtMillis() <= nowMillis
                && nowMillis - fresh.capturedAtMillis() <= 2500 && observed.width() == fresh.width()
                && observed.height() == fresh.height() && observed.stride() == fresh.stride()
                && Objects.equals(observed.geometry(), fresh.geometry());
    }

    private boolean validFrame(DesktopAction action) {
        return frame != null && !action.observationId().isBlank() && action.windowGeneration() == generation
                && action.contentRevision() == frame.contentRevision() && frame.capturedAtMillis() <= System.currentTimeMillis()
                && System.currentTimeMillis() - frame.capturedAtMillis() <= 2500
                && (action.kind() == DesktopAction.Kind.KEY || action.kind() == DesktopAction.Kind.TYPE && action.x() < 0 && action.y() < 0
                || action.x() >= 0 && action.y() >= 0 && action.x() < frame.width() && action.y() < frame.height());
    }

    private static WindowsWindows.Rect rect(NativeWindow window) {
        return new WindowsWindows.Rect(window.x(), window.y(), window.x() + window.width(), window.y() + window.height());
    }

    private static DesktopActionResult stale(DesktopAction action, String detail) {
        return new DesktopActionResult(DesktopActionResult.Status.STALE_FRAME, detail, action.windowGeneration())
                .withContext(DesktopActionResult.Mode.NONE, action.observationId(), DesktopActionResult.NextStep.OBSERVE);
    }

    private static DesktopActionResult denied(DesktopAction action, String detail) {
        return new DesktopActionResult(DesktopActionResult.Status.DENIED, detail, action.windowGeneration())
                .withContext(DesktopActionResult.Mode.NONE, action.observationId(), DesktopActionResult.NextStep.OBSERVE);
    }

    @Override public synchronized void close() {
        if (closed) return;
        try { input.restore(); }
        finally { closed = true; frame = null; previousPixels = null; uia.clear(); capture.close(); }
    }
}
