package com.javaclaw.desktop.nativebridge;

import com.javaclaw.desktop.api.DesktopActionResult;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DesktopBridgeActionAdmissionTest {
    @Test
    void aPlatformAcknowledgementConfirmsTransportWithoutClaimingAControlReadback() throws Exception {
        String header = java.nio.file.Files.readString(java.nio.file.Path.of("src/main/native/desktop_bridge.h"));
        assertTrue(header.contains("#define JC_DESKTOP_ABI_VERSION 6"),
                "old clients must not read the new ACCEPTED code as FAILED/NOT_SENT");
        assertTrue(header.contains("JC_ACTION_ACCEPTED = 6"));
        var result = new DesktopActionResult(
                DesktopBridge.actionStatus(6),
                "AXPress accepted; target effect is not confirmed", 1);

        assertEquals(DesktopActionResult.Status.ACCEPTED, result.status());
        assertNotEquals(DesktopActionResult.Status.VERIFIED, result.status());
        assertEquals(DesktopActionResult.Delivery.SENT, result.delivery());
        assertTrue(result.dispatchAttempted());
        assertEquals(DesktopActionResult.Reason.NONE, result.reason());
        assertThrows(IllegalArgumentException.class, () -> new DesktopActionResult(
                DesktopActionResult.Status.ACCEPTED, "missing dispatch proof", 1,
                DesktopActionResult.Mode.NONE, DesktopActionResult.Reason.NONE,
                false, "observation", DesktopActionResult.NextStep.OBSERVE));
    }

    @Test
    void unknownNativeCodesCannotCreateDefiniteNotSentRetryPermission() {
        for (int code : new int[]{-1, 7, Integer.MAX_VALUE}) {
            var result = new DesktopActionResult(DesktopBridge.actionStatus(code), "unknown", 1);
            assertEquals(DesktopActionResult.Status.UNKNOWN, result.status());
            assertEquals(DesktopActionResult.Delivery.MAYBE_SENT, result.delivery());
            assertTrue(result.dispatchAttempted());
        }
        var rejected = new DesktopActionResult(
                DesktopBridge.actionStatus(5), "pre-dispatch failure", 1);
        assertEquals(DesktopActionResult.Status.FAILED, rejected.status());
        assertEquals(DesktopActionResult.Delivery.NOT_SENT, rejected.delivery());
        assertFalse(rejected.dispatchAttempted());
    }
}
