package com.javaclaw.desktop.nativebridge;

import com.javaclaw.desktop.api.*;
import com.javaclaw.desktop.ffm.SystemDesktopApi;
import com.javaclaw.desktop.ffm.SystemDesktopSession;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

final class DesktopBridgeTestSupport {
    static final String OBS = "11111111-1111-1111-1111-111111111111";
    static final DesktopBridge.NativeWindow WINDOW = new DesktopBridge.NativeWindow(
            42, 99, 314, 10, 20, 80, 60, DesktopTarget.VISIBLE, "Reader", "", "com.example.reader");
    static DesktopAction action(DesktopAction.Kind kind, String element, DesktopAction.TextOperation operation) {
        return new DesktopAction(kind, 1, 1, 1, 1, 1, "text", 1, OBS, element, 1, operation);
    }
    static DesktopAction click() {
        return action(DesktopAction.Kind.CLICK, OBS + ":e27", DesktopAction.TextOperation.INSERT_TEXT);
    }
    static DesktopFrame frame(String target) {
        return new DesktopFrame(target, 1, 100, 80, 60, 320, new byte[19_200], 1);
    }
    static final class Api implements SystemDesktopApi {
        final Session session = new Session();
        int probes, requests, launchCalls;
        DesktopInputPolicy policy;
        Function<String, DesktopApplicationLaunch> launcher = name -> new DesktopApplicationLaunch(42, name, "accepted");
        public DesktopAvailability availability(boolean request, DesktopInputPolicy value) {
            if (request) requests++; else probes++;
            policy = value;
            return new DesktopAvailability(true, "fake", DesktopAvailability.CAPTURE
                    | DesktopAvailability.SEMANTIC_INPUT | DesktopAvailability.PUBLIC_SEMANTIC, "");
        }
        public List<DesktopBridge.NativeWindow> windows() { return List.of(WINDOW); }
        public DesktopApplicationCatalog applications() { return new DesktopApplicationCatalog(List.of(), false); }
        public DesktopApplicationLaunch launch(String application) { launchCalls++; return launcher.apply(application); }
        public Optional<Boolean> windowExists(long pid, long window, long instance) {
            return Optional.of(pid == 42 && window == 99 && instance == 314);
        }
        public SystemDesktopSession open(DesktopBridge.NativeWindow window) { return session; }
    }
    static final class Session implements SystemDesktopSession {
        DesktopBridge.NativeWindow window = WINDOW;
        Optional<DesktopFrame> captured = Optional.of(frame("target"));
        Runnable afterCapture = () -> { };
        int captures, attempts, closes, prepares, restores;
        DesktopAction lastAction;
        DesktopActionResult result = new DesktopActionResult(DesktopActionResult.Status.ACCEPTED, "accepted", 1);
        RuntimeException failure;
        public DesktopBridge.NativeWindow current() { return window; }
        public Optional<DesktopFrame> capture(String target, int timeout) {
            captures++; afterCapture.run(); return captured;
        }
        public List<DesktopElement> elements(DesktopFrame frame) { return List.of(); }
        public Optional<Boolean> targetActive() { return Optional.of(false); }
        public void prepareForeground() { prepares++; }
        public void restoreForeground() { restores++; }
        public DesktopActionResult perform(DesktopAction action, boolean foreground) {
            attempts++; lastAction = action;
            if (failure != null) throw failure;
            return result;
        }
        public void close() { closes++; }
    }
    private DesktopBridgeTestSupport() { }
}
