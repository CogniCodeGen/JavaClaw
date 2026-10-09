package com.javaclaw.desktop.nativebridge;

import com.javaclaw.desktop.api.*;
import org.junit.jupiter.api.Test;
import static com.javaclaw.desktop.nativebridge.DesktopBridgeTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

class DesktopBridgeSessionTest {
    @Test void probeDoesNotRequestPermissionsAndRequestRetainsPolicy() {
        var api = new Api();
        var bridge = new DesktopBridge(api);
        assertTrue(bridge.probe("fake").available());
        assertEquals(1, api.probes);
        assertEquals(0, api.requests);
        bridge.probe("fake", DesktopInputPolicy.SYSTEM_EXPLICIT);
        assertEquals(DesktopInputPolicy.SYSTEM_EXPLICIT, api.policy);
        assertEquals(0, api.requests);
        bridge.requestPermissions("fake", DesktopInputPolicy.SYSTEM_EXPLICIT);
        assertEquals(1, api.requests);
        assertEquals(DesktopInputPolicy.SYSTEM_EXPLICIT, api.policy);
    }

    @Test void captureIsBoundToOpenedProcessAndWindowBeforeAndAfterCapture() {
        var api = new Api();
        var bridge = new DesktopBridge(api);
        try (var session = bridge.open(WINDOW)) {
            var captured = bridge.pollCaptured(session, "target", 500).orElseThrow();
            assertEquals(WINDOW.windowId(), captured.windowId());
            assertEquals("target", captured.frame().targetId());
            api.session.afterCapture = () -> api.session.window = new DesktopBridge.NativeWindow(
                    43, 99, 314, 10, 20, 80, 60, 2, "Reader", "", "com.example.reader");
            assertThrows(IllegalStateException.class, () -> bridge.pollCaptured(session, "target", 500));
            assertEquals(2, api.session.captures);
        }
    }

    @Test void wrongTargetFrameAndReplacementAtOpenAreRejected() {
        var api = new Api();
        var bridge = new DesktopBridge(api);
        try (var session = bridge.open(WINDOW)) {
            api.session.captured = java.util.Optional.of(frame("different"));
            assertThrows(IllegalStateException.class, () -> bridge.poll(session, "target", 1));
        }
        api.session.window = new DesktopBridge.NativeWindow(42, 100, 314, 10, 20, 80, 60,
                2, "Reader", "", "com.example.reader");
        assertThrows(IllegalStateException.class, () -> bridge.open(WINDOW));
        assertEquals(2, api.session.closes, "invalid open must close the system session it acquired");
    }

    @Test void closeIsIdempotentAndStopsCaptureOrInput() {
        var api = new Api();
        var bridge = new DesktopBridge(api);
        var session = bridge.open(WINDOW);
        session.close(); session.close();
        assertEquals(1, api.session.closes);
        assertThrows(IllegalStateException.class, () -> bridge.poll(session, "target", 1));
        assertEquals(DesktopActionResult.Delivery.NOT_SENT, bridge.perform(session, click(), true).delivery());
        assertEquals(0, api.session.captures);
        assertEquals(0, api.session.attempts);
    }

    @Test void existenceRequiresCompleteProcessInstanceIdentity() {
        var bridge = new DesktopBridge(new Api());
        assertTrue(bridge.windowExists(42, 99, 0).isEmpty());
        assertTrue(bridge.windowExists(0, 99, 314).isEmpty());
        assertEquals(java.util.Optional.of(true), bridge.windowExists(42, 99, 314));
        assertEquals(java.util.Optional.of(false), bridge.windowExists(42, 99, 315));
    }
}
