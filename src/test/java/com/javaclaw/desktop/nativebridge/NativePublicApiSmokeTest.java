package com.javaclaw.desktop.nativebridge;

import com.javaclaw.desktop.api.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import static org.junit.jupiter.api.Assertions.*;

/** Opt-in direct system FFM check; no application launch, input or permission request. */
@EnabledIfSystemProperty(named = "javaclaw.native.public.tests", matches = "true")
class NativePublicApiSmokeTest {
    @Test void loadsSystemApiAndFailsClosedWithoutTarget() throws Exception {
        String platform = System.getProperty("os.name").startsWith("Mac") ? "macos" : "windows";
        var bridge = new DesktopBridge(platform);
        assertTrue(bridge.supportsPublicApi());
        DesktopAvailability status = bridge.probe(platform);
        assertEquals(platform, status.providerId());
        var set = new DesktopAction(DesktopAction.Kind.TYPE, 1, 1, 0, 0, 0,
                "", 1, "observation", "observation:e1", 1, DesktopAction.TextOperation.SET_TEXT);
        for (boolean foreground : new boolean[] {false, true}) {
            DesktopActionResult rejected = bridge.perform(null, set, foreground);
            assertEquals(DesktopActionResult.Status.STALE_FRAME, rejected.status());
            assertFalse(rejected.dispatchAttempted());
            assertEquals(DesktopActionResult.Delivery.NOT_SENT, rejected.delivery());
        }
        // Only query metadata. The test does not expose titles or application names in its output.
        for (var window : bridge.listWindows()) {
            assertTrue(window.processId() > 0);
            assertNotEquals(0, window.processInstanceId());
            assertNotEquals(0, window.windowId());
        }
        assertTrue(bridge.listApplications().applications().size() <= 256);
    }
}
