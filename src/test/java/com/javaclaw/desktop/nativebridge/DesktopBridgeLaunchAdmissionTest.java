package com.javaclaw.desktop.nativebridge;

import com.javaclaw.desktop.api.DesktopApplicationLaunchRejectedException;
import com.javaclaw.desktop.api.DesktopApplicationLaunchUncertainException;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class DesktopBridgeLaunchAdmissionTest {
    @Test
    void onlyKnownPreDispatchNativeCodesProveRejection() {
        Map<Integer, String> rejectedCodes = Map.of(-1, "INVALID_ARGUMENTS", -2, "UNSUPPORTED",
                -3, "APPLICATION_NOT_FOUND", -4, "AMBIGUOUS_APPLICATION");
        rejectedCodes.forEach((code, reason) -> {
            var rejected = assertThrows(DesktopApplicationLaunchRejectedException.class,
                    () -> DesktopBridge.launchResult(code, 0, "", "localized arbitrary detail"));
            assertEquals(code.intValue(), rejected.nativeCode());
            assertEquals(reason, rejected.reasonCode());
            assertFalse(rejected.dispatchAttempted());
        });
    }

    @Test
    void postDispatchAndUnknownNativeCodesStayUncertainEvenWithNotFoundText() {
        for (int code : new int[]{-5, -6, -7, -8, -99, 1}) {
            var uncertain = assertThrows(DesktopApplicationLaunchUncertainException.class,
                    () -> DesktopBridge.launchResult(code, 202, "com.example.reader",
                            "Exact application not found; NOT_SENT"));
            assertEquals(202, uncertain.processId());
            assertEquals("com.example.reader", uncertain.applicationId());
        }
    }

    @Test
    void successRequiresAValidProcessWithoutClaimingWindowContent() {
        assertThrows(DesktopApplicationLaunchUncertainException.class,
                () -> DesktopBridge.launchResult(0, 0, "com.example.reader", "accepted"));
        var launched = DesktopBridge.launchResult(0, 202, "com.example.reader", "accepted");
        assertEquals(202, launched.processId());
        assertEquals("com.example.reader", launched.applicationId());
    }
}
