package com.javaclaw.desktop.ffm.macos;

import com.javaclaw.desktop.api.DesktopAction;
import com.javaclaw.desktop.api.DesktopActionResult;
import com.javaclaw.desktop.api.DesktopElement;
import com.javaclaw.desktop.api.DesktopFrame;
import com.javaclaw.desktop.ffm.DesktopSemanticAction;
import com.javaclaw.desktop.ffm.SystemDesktopSession;
import com.javaclaw.desktop.nativebridge.DesktopBridge.NativeWindow;
import java.util.List;
import java.util.Optional;

/** Java owns the target binding, frame revisions, semantic snapshots and dispatch lifecycle. */
final class MacDesktopSession implements SystemDesktopSession {
    private final MacNative api;
    private final MacWindows windows;
    private final MacCapture capture;
    private final MacAccessibility ax;
    private final MacInput input;
    private NativeWindow current;
    private DesktopFrame latest;
    private long generation = 1;
    private long revision = 1;
    private boolean closed;
    private boolean unavailable;

    MacDesktopSession(MacNative api, MacWindows windows, NativeWindow target) {
        this.api = api; this.windows = windows; current = target;
        capture = new MacCapture(api, windows);
        ax = new MacAccessibility(api);
        input = new MacInput(api, windows, ax);
    }

    @Override public synchronized NativeWindow current() {
        requireOpen();
        try (var pool = api.pool()) { refresh(); return current; }
    }

    private void refresh() {
        NativeWindow changed;
        try { changed = windows.refresh(current); }
        catch (RuntimeException failure) {
            unavailable = true; generation++; latest = null; ax.close(); throw failure;
        }
        if (changed == null) throw new IllegalStateException("Target window disappeared");
        if (changed.x() != current.x() || changed.y() != current.y()
                || changed.width() != current.width() || changed.height() != current.height()
                || (changed.flags() & 3) != (current.flags() & 3)) {
            generation++; latest = null; ax.close();
        }
        current = changed;
    }

    @Override public synchronized Optional<DesktopFrame> capture(String targetId, int timeoutMillis) {
        requireOpen();
        try (var pool = api.pool()) {
            refresh();
            if ((current.flags() & 2) == 0 || (current.flags() & 1) != 0) { latest = null; return Optional.empty(); }
            NativeWindow before = current;
            long capturedGeneration = generation;
            MacCapture.Pixels pixels = capture.capture(current, timeoutMillis);
            refresh();
            if (capturedGeneration != generation || !sameGeometry(before, current)
                    || (current.flags() & 2) == 0) { latest = null; return Optional.empty(); }
            windows.requireOriginal(current);
            if (!pixels.sameContent(latest)) revision++;
            latest = pixels.frame(targetId, generation, revision);
            return Optional.of(latest);
        }
    }

    @Override public synchronized List<DesktopElement> elements(DesktopFrame frame) {
        requireOpen();
        if (latest == null || frame.windowGeneration() != generation
                || frame.contentRevision() != latest.contentRevision()
                || frame.capturedAtMillis() != latest.capturedAtMillis()) return List.of();
        try (var pool = api.pool()) { return ax.observe(current, frame); }
    }

    @Override public synchronized String diagnostics() { return ax.diagnostics(); }

    @Override public synchronized Optional<Boolean> targetActive() {
        requireOpen();
        try (var pool = api.pool()) {
            windows.requireOriginal(current);
            long active = input.activePid();
            return active <= 0 ? Optional.empty() : Optional.of(active == current.processId());
        } catch (RuntimeException unavailable) { return Optional.empty(); }
    }

    @Override public synchronized void prepareForeground() {
        requireOpen();
        try (var pool = api.pool()) { refresh(); input.prepare(current); }
    }

    @Override public synchronized void restoreForeground() {
        if (closed) return;
        try (var pool = api.pool()) { input.restore(current); }
    }

    @Override public synchronized DesktopActionResult perform(DesktopAction action, boolean foreground) {
        requireOpen();
        if (!foreground && DesktopSemanticAction.admit(action).isEmpty())
            return result(DesktopActionResult.Status.UNSUPPORTED, action,
                    "Background input requires one observed semantic element and an advertised operation");
        if (latest == null || action.windowGeneration() != generation
                || action.contentRevision() != latest.contentRevision()
                || latest.capturedAtMillis() > System.currentTimeMillis()
                || System.currentTimeMillis() - latest.capturedAtMillis() > 2500
                || action.x() < 0 || action.y() < 0 || action.x() >= latest.width() || action.y() >= latest.height())
            return result(DesktopActionResult.Status.STALE_FRAME, action, "Observe the current target before sending input");
        try (var pool = api.pool()) {
            windows.requireOriginal(current);
            NativeWindow now = windows.refresh(current);
            if (now == null || !sameGeometry(now, current)
                    || (now.flags() & 2) == 0)
                return result(DesktopActionResult.Status.STALE_FRAME, action, "Selected window geometry or visibility changed");
            if (foreground) {
                DesktopFrame inputFrame = latest;
                if (action.kind() != DesktopAction.Kind.CLICK) {
                    MacCapture.Pixels refreshed;
                    try { refreshed = capture.capture(current, 1000); }
                    catch (SecurityException deniedCapture) {
                        return result(DesktopActionResult.Status.DENIED, action, "Capture permission is unavailable; no input was sent");
                    } catch (RuntimeException | LinkageError unavailableCapture) {
                        return result(DesktopActionResult.Status.STALE_FRAME, action,
                                "Capture could not be refreshed; no system input was sent");
                    }
                    if (!refreshed.sameContent(inputFrame) || !windows.geometryCurrent(current))
                        return result(DesktopActionResult.Status.STALE_FRAME, action,
                                "Window pixels changed before system input; observe again");
                    inputFrame = refreshed.frame(inputFrame.targetId(), generation, revision);
                    latest = inputFrame;
                }
                DesktopFrame beforeInput = inputFrame;
                return input.perform(current, action, beforeInput, () -> {
                    try {
                        return windows.geometryCurrent(current) && capture.capture(current, 1000).sameContent(beforeInput)
                                && windows.geometryCurrent(current);
                    } catch (RuntimeException | LinkageError unavailableCapture) { return false; }
                });
            }
            long active = input.activePid();
            if (active <= 0 || active == current.processId()) return result(DesktopActionResult.Status.DENIED,
                    action, "Target is active or foreground identity is unavailable");
            DesktopFrame fresh = capture.capture(current, 1000).frame(latest.targetId(), generation, revision);
            windows.requireOriginal(current);
            NativeWindow afterCapture = windows.refresh(current);
            if (afterCapture == null || !sameGeometry(afterCapture, current))
                return result(DesktopActionResult.Status.STALE_FRAME, action, "Window moved during semantic validation capture");
            DesktopActionResult result = ax.perform(current, action, fresh,
                    input.activePid() == current.processId(), () ->
                            windows.instance(current.processId()) == current.processInstanceId()
                                    && input.activePid() > 0 && input.activePid() != current.processId()
                                    && windows.geometryCurrent(current)
                                    && fresh.capturedAtMillis() <= System.currentTimeMillis()
                                    && System.currentTimeMillis() - fresh.capturedAtMillis() <= 2500);
            if (result.dispatchAttempted() && (input.activePid() <= 0 || input.activePid() == current.processId()
                    || windows.instance(current.processId()) != current.processInstanceId()))
                return result(DesktopActionResult.Status.UNKNOWN, action,
                        "Target activated or its identity changed after semantic dispatch; reconcile before continuing");
            return result;
        } finally {
            // A token is single-use even if the OS reports an uncertain or rejected action.
            ax.close();
            if (foreground) restoreForeground();
        }
    }

    private DesktopActionResult result(DesktopActionResult.Status status, DesktopAction action, String detail) {
        return new DesktopActionResult(status, detail, action.windowGeneration()).withContext(
                DesktopActionResult.Mode.NONE, action.observationId(),
                status == DesktopActionResult.Status.UNKNOWN ? DesktopActionResult.NextStep.RECONCILE
                        : DesktopActionResult.NextStep.OBSERVE);
    }
    private static boolean sameGeometry(NativeWindow before, NativeWindow after) {
        return before.x() == after.x() && before.y() == after.y()
                && before.width() == after.width() && before.height() == after.height()
                && before.windowId() == after.windowId() && before.processId() == after.processId()
                && before.processInstanceId() == after.processInstanceId();
    }
    private void requireOpen() {
        if (closed || unavailable) throw new IllegalStateException(closed
                ? "macOS desktop session is closed" : "Selected window is unavailable; open a newly discovered target");
    }
    @Override public synchronized void close() {
        if (closed) return;
        restoreForeground(); ax.close(); latest = null; closed = true;
    }
}
