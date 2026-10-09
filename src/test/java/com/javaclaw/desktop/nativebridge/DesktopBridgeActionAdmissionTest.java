package com.javaclaw.desktop.nativebridge;

import com.javaclaw.desktop.api.DesktopActionResult;
import org.junit.jupiter.api.Test;
import static com.javaclaw.desktop.nativebridge.DesktopBridgeTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

class DesktopBridgeActionAdmissionTest {
    @Test void platformAcknowledgementConfirmsTransportWithoutClaimingControlReadback() {
        var api = new Api();
        var bridge = new DesktopBridge(api);
        try (var session = bridge.open(WINDOW)) {
            var result = bridge.perform(session, click(), false);
            assertEquals(DesktopActionResult.Status.ACCEPTED, result.status());
            assertEquals(DesktopActionResult.Delivery.SENT, result.delivery());
            assertTrue(result.dispatchAttempted());
            assertEquals(1, api.session.attempts);
        }
        assertThrows(IllegalArgumentException.class, () -> new DesktopActionResult(
                DesktopActionResult.Status.ACCEPTED, "missing dispatch proof", 1,
                DesktopActionResult.Mode.NONE, DesktopActionResult.Reason.NONE,
                false, OBS, DesktopActionResult.NextStep.OBSERVE));
    }

    @Test void attemptedFailureCannotCreateDefiniteNotSentRetryPermission() {
        for (var status : new DesktopActionResult.Status[] { DesktopActionResult.Status.FAILED,
                DesktopActionResult.Status.DENIED, DesktopActionResult.Status.STALE_FRAME }) {
            var api = new Api();
            api.session.result = new DesktopActionResult(status, "result lost after dispatch", 1,
                    DesktopActionResult.Mode.BACKGROUND_SEMANTIC, DesktopActionResult.Reason.PLATFORM_FAILURE,
                    true, OBS, DesktopActionResult.NextStep.OBSERVE);
            var bridge = new DesktopBridge(api);
            try (var session = bridge.open(WINDOW)) {
                var result = bridge.perform(session, click(), false);
                assertEquals(DesktopActionResult.Status.UNKNOWN, result.status());
                assertEquals(DesktopActionResult.Delivery.MAYBE_SENT, result.delivery());
                assertEquals(1, api.session.attempts);
            }
        }
    }

    @Test void missingOrReplacedTargetPreventsDispatch() {
        var api = new Api();
        var bridge = new DesktopBridge(api);
        assertEquals(DesktopActionResult.Delivery.NOT_SENT, bridge.perform(null, click(), true).delivery());
        try (var session = bridge.open(WINDOW)) {
            api.session.window = new DesktopBridge.NativeWindow(42, 99, 315, 10, 20, 80, 60,
                    2, "Reader", "", "com.example.reader");
            assertEquals(DesktopActionResult.Status.STALE_FRAME, bridge.perform(session, click(), true).status());
            assertEquals(0, api.session.attempts);
        }
    }

    @Test void lostResultPropagatesWithoutRetryOrFallback() {
        var api = new Api();
        api.session.failure = new IllegalStateException("result lost after sending");
        var bridge = new DesktopBridge(api);
        try (var session = bridge.open(WINDOW)) {
            assertSame(api.session.failure, assertThrows(IllegalStateException.class,
                    () -> bridge.perform(session, click(), false)));
            assertEquals(1, api.session.attempts);
            assertEquals(0, api.session.prepares);
        }
    }
}
