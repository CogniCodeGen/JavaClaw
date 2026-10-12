package com.javaclaw.desktop.nativebridge;

import com.javaclaw.desktop.api.*;
import org.junit.jupiter.api.Test;
import static com.javaclaw.desktop.nativebridge.DesktopBridgeTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

class DesktopBridgeLaunchAdmissionTest {
    @Test void typedPreDispatchRejectionIsPreservedWithoutParsingMessage() {
        for (var reason : DesktopApplicationLaunchRejectedException.Reason.values()) {
            var api = new Api();
            var rejected = new DesktopApplicationLaunchRejectedException(reason, "arbitrary localized detail");
            api.launcher = name -> { throw rejected; };
            assertSame(rejected, assertThrows(DesktopApplicationLaunchRejectedException.class,
                    () -> new DesktopBridge(api).launchApplication("Reader")));
            assertFalse(rejected.dispatchAttempted());
            assertEquals(1, api.launchCalls);
        }
    }

    @Test void postDispatchUncertaintyCannotBeDowngradedByNotFoundTextOrRetried() {
        var api = new Api();
        var uncertain = new DesktopApplicationLaunchUncertainException(
                "Exact application not found; NOT_SENT", 202, "com.example.reader", null);
        api.launcher = name -> { throw uncertain; };
        assertSame(uncertain, assertThrows(DesktopApplicationLaunchUncertainException.class,
                () -> new DesktopBridge(api).launchApplication("Reader")));
        assertEquals(202, uncertain.processId());
        assertEquals(1, api.launchCalls);
    }

    @Test void successRequiresValidProcessWithoutClaimingWindowContent() {
        assertThrows(IllegalArgumentException.class, () -> new DesktopApplicationLaunch(0, "", ""));
        var api = new Api();
        var launch = new DesktopBridge(api).launchApplication("com.example.reader");
        assertEquals(42, launch.processId());
        assertEquals("com.example.reader", launch.applicationId());
        assertEquals(0, api.session.captures);
    }
}
