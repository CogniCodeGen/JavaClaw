package com.javaclaw.desktop.nativebridge;

import com.javaclaw.desktop.api.DesktopAction;
import com.javaclaw.desktop.api.DesktopActionResult;
import com.javaclaw.desktop.api.DesktopFrame;
import com.javaclaw.desktop.api.DesktopFrameGeometry;
import com.javaclaw.desktop.api.DesktopSurfaceSnapshot;
import com.javaclaw.desktop.api.DesktopTarget;
import com.javaclaw.desktop.spi.DesktopClickGuard;
import com.javaclaw.desktop.spi.DesktopPlatformSession;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DesktopClickGuardTest {
    private static DesktopFrame frame(long capturedAt, long revision) {
        byte[] pixels = new byte[80 * 30]; // forty BGRA bytes of padding per row
        for (int y = 0; y < 30; y++)
            for (int x = 0; x < 10; x++) pixels[y * 80 + x * 4] = (byte) (x + y);
        return new DesktopFrame("window", 3, capturedAt, 10, 30, 80, pixels, revision,
                new DesktopFrameGeometry(5, 15, 0, 0, 10, 30, true));
    }

    private static DesktopAction click(int clicks) {
        return new DesktopAction(DesktopAction.Kind.CLICK, 1, 15, 1, clicks, 0,
                "", 3, "observation", "", 7);
    }

    @Test void capturesCompactImmutablePixelsWithClippedMarginAndPadding() {
        DesktopClickGuard guard = DesktopClickGuard.capture(frame(100, 7), 1, 15, 1, 1);
        assertEquals("window", guard.targetId());
        assertEquals(0, guard.x());
        assertEquals(7, guard.y());
        assertEquals(10, guard.width());
        assertEquals(17, guard.height());
        byte[] pixels = guard.bgra();
        assertEquals(680, pixels.length);
        assertEquals(7, pixels[0]);
        assertEquals(8, pixels[40]);
        pixels[0] = 99;
        assertEquals(7, guard.bgra()[0]);
    }

    @Test void allowsRevisionAdvanceFromAnimationOutsideTheSelectedRegion() {
        DesktopFrame baseline = frame(100, 7);
        DesktopClickGuard guard = DesktopClickGuard.capture(baseline, 1, 15, 1, 1);
        byte[] outside = baseline.bgraPremultiplied();
        outside[0] = 99;
        DesktopFrame animated = new DesktopFrame("window", 3, 110, 10, 30, 80,
                outside, 8, baseline.geometry());
        assertTrue(guard.matches(click(1), animated, 120));
        outside[7 * 80] = 99;
        DesktopFrame changedTarget = new DesktopFrame("window", 3, 110, 10, 30, 80,
                outside, 8, baseline.geometry());
        assertFalse(guard.matches(click(1), changedTarget, 120));
    }

    @Test void rejectsIdentityGeometryGenerationRevisionAndTimestampChanges() {
        DesktopFrame baseline = frame(100, 7);
        DesktopClickGuard guard = DesktopClickGuard.capture(baseline, 1, 15, 1, 1);
        byte[] pixels = baseline.bgraPremultiplied();
        assertFalse(guard.matches(click(1), new DesktopFrame("other", 3, 110, 10, 30, 80,
                pixels, 8, baseline.geometry()), 120));
        assertFalse(guard.matches(click(1), new DesktopFrame("window", 4, 110, 10, 30, 80,
                pixels, 8, baseline.geometry()), 120));
        assertFalse(guard.matches(click(1), new DesktopFrame("window", 3, 110, 10, 30, 80,
                pixels, 6, baseline.geometry()), 120));
        assertFalse(guard.matches(click(1), new DesktopFrame("window", 3, 99, 10, 30, 80,
                pixels, 8, baseline.geometry()), 120));
        assertFalse(guard.matches(click(1), new DesktopFrame("window", 3, 121, 10, 30, 80,
                pixels, 8, baseline.geometry()), 120));
        assertFalse(guard.matches(click(1), frame(100, 7), 2_601));
        assertFalse(guard.matches(click(1), new DesktopFrame("window", 3, 110, 10, 30, 80,
                pixels, 8, new DesktopFrameGeometry(6, 15, 0, 0, 10, 30, true)), 120));
        assertFalse(guard.matches(click(1), new DesktopFrame("window", 3, 110, 10, 30, 40,
                new byte[40 * 30], 8, baseline.geometry()), 120));
        assertFalse(guard.matches(new DesktopAction(DesktopAction.Kind.CLICK, 1, 0, 1, 1, 0,
                "", 3, "observation", "", 7), baseline, 120));
    }

    @Test void rejectsClickOutsideTheCapturedContentRectangle() {
        DesktopFrame baseline = new DesktopFrame("window", 3, 100, 10, 30, 80,
                frame(100, 7).bgraPremultiplied(), 7,
                new DesktopFrameGeometry(5, 15, 2, 0, 8, 30, true));
        assertFalse(DesktopClickGuard.capture(baseline, 1, 15, 1, 1)
                .matches(click(1), baseline, 120));
    }

    @Test void rejectsOversizedOrOverflowingEvidenceAndMissingPixels() {
        assertThrows(IllegalArgumentException.class, () -> new DesktopClickGuard("window", 1, 1, 1,
                100000000, 100000000, 400000000, null, 0, 0, 100000000, 100000000, new byte[0]));
        assertThrows(IllegalArgumentException.class,
                () -> DesktopClickGuard.capture(frame(100, 7), 9, 0, 2, 1));
        assertThrows(IllegalArgumentException.class, () -> new DesktopClickGuard("window", 1, 1, 1,
                1, 1, 4, null, 0, 0, 1, 1, new byte[3]));
    }

    @Test void rejectsCaptureIdentityThatDoesNotBelongToTheBaselineFrame() {
        DesktopFrame baseline = frame(100, 7);
        DesktopSurfaceSnapshot otherRevision = new DesktopSurfaceSnapshot("test", "process-instance",
                "native-window", "window", "test.app", 3, 8, 100);
        assertThrows(IllegalArgumentException.class,
                () -> DesktopClickGuard.capture(baseline, otherRevision, 1, 15, 1, 1));
        DesktopSurfaceSnapshot otherCapture = new DesktopSurfaceSnapshot("test", "process-instance",
                "native-window", "window", "test.app", 3, 7, 101);
        assertThrows(IllegalArgumentException.class,
                () -> DesktopClickGuard.capture(baseline, otherCapture, 1, 15, 1, 1));
    }

    @Test void missingGeometryOrBaselineWindowIdentityCannotDispatch() {
        long now = System.currentTimeMillis();
        DesktopFrame pixelsOnly = new DesktopFrame("window", 3, now, 10, 30, 80,
                frame(now, 7).bgraPremultiplied(), 7);
        var missingGeometry = new Session(pixelsOnly, pixelsOnly);
        assertNotSent(missingGeometry);
        var missingIdentity = new Session(frame(now, 7), frame(now, 8));
        DesktopActionResult result = missingIdentity.performClick(click(1), false,
                DesktopClickGuard.capture(missingIdentity.captured, 1, 15, 1, 1));
        assertEquals(DesktopActionResult.Delivery.NOT_SENT, result.delivery());
        assertEquals(0, missingIdentity.dispatches);
    }

    @Test void defaultPlatformPathRefreshesInJavaAndDispatchesExactlyOnce() {
        long now = System.currentTimeMillis();
        var platform = new Session(frame(now, 7), frame(now, 8));
        DesktopActionResult result = platform.performClick(click(2), true,
                DesktopClickGuard.capture(platform.captured, platform.baselineSurface, 1, 15, 1, 1));
        assertEquals(DesktopActionResult.Status.ACCEPTED, result.status());
        assertEquals(1, platform.polls);
        assertEquals(1, platform.dispatches);
        assertEquals(8, platform.dispatched.contentRevision());
        assertEquals(2, platform.dispatched.clicks(), "a double click remains one provider invocation");
    }

    @Test void refusesAnUnavailableOrDifferentCapturedWindowWithoutDispatch() {
        long now = System.currentTimeMillis();
        var missingFrame = new Session(frame(now, 7), null);
        assertNotSent(missingFrame);
        var changedSurface = new Session(frame(now, 7), frame(now, 8));
        changedSurface.newSurfaceId = "different-window";
        assertNotSent(changedSurface);
        var noIdentity = new Session(frame(now, 7), frame(now, 8));
        noIdentity.proveSurface = false;
        assertNotSent(noIdentity);
        var wrongCaptureIdentity = new Session(frame(now, 7), frame(now, 8));
        wrongCaptureIdentity.surfaceRevisionOffset = 1;
        assertNotSent(wrongCaptureIdentity);
        var replacedProcessBeforeRefresh = new Session(frame(now, 7), frame(now, 8));
        replacedProcessBeforeRefresh.runtimeId = "replacement-process-instance";
        assertNotSent(replacedProcessBeforeRefresh);
    }

    @Test void capturePermissionFailureIsNotAnUnknownInputDelivery() {
        long now = System.currentTimeMillis();
        var platform = new Session(frame(now, 7), frame(now, 8));
        platform.captureFailure = new SecurityException("capture denied");
        DesktopActionResult result = platform.performClick(click(1), false,
                DesktopClickGuard.capture(platform.captured, platform.baselineSurface, 1, 15, 1, 1));
        assertEquals(DesktopActionResult.Status.DENIED, result.status());
        assertEquals(DesktopActionResult.Delivery.NOT_SENT, result.delivery());
        assertEquals(0, platform.dispatches);
    }

    @Test void preservesUnknownDoubleClickDeliveryWithoutRetry() {
        long now = System.currentTimeMillis();
        var platform = new Session(frame(now, 7), frame(now, 8));
        platform.result = new DesktopActionResult(DesktopActionResult.Status.UNKNOWN,
                "first click may have been sent", 3);
        DesktopActionResult result = platform.performClick(click(2), true,
                DesktopClickGuard.capture(platform.captured, platform.baselineSurface, 1, 15, 1, 1));
        assertEquals(DesktopActionResult.Status.UNKNOWN, result.status());
        assertEquals(DesktopActionResult.Delivery.MAYBE_SENT, result.delivery());
        assertEquals(1, platform.dispatches);
    }

    private static void assertNotSent(Session platform) {
        DesktopActionResult result = platform.performClick(click(1), false,
                DesktopClickGuard.capture(platform.captured, platform.baselineSurface, 1, 15, 1, 1));
        assertEquals(DesktopActionResult.Status.STALE_FRAME, result.status());
        assertEquals(DesktopActionResult.Delivery.NOT_SENT, result.delivery());
        assertEquals(0, platform.dispatches);
    }

    private static final class Session implements DesktopPlatformSession {
        private final DesktopTarget target = new DesktopTarget("test", "window", 10, "App", "Window",
                0, 0, 5, 15, DesktopTarget.VISIBLE, "test.app");
        DesktopFrame captured;
        final DesktopFrame next;
        final DesktopSurfaceSnapshot baselineSurface;
        String runtimeId = "process-instance";
        DesktopAction dispatched;
        int polls;
        int dispatches;
        String newSurfaceId;
        boolean proveSurface = true;
        long surfaceRevisionOffset;
        RuntimeException captureFailure;
        DesktopActionResult result = new DesktopActionResult(DesktopActionResult.Status.ACCEPTED, "sent", 3);
        Session(DesktopFrame captured, DesktopFrame next) {
            this.captured = captured;
            this.next = next;
            this.baselineSurface = new DesktopSurfaceSnapshot("test", runtimeId, "native-window", "window", "test.app",
                    captured.windowGeneration(), captured.contentRevision(), captured.capturedAtMillis());
        }
        @Override public DesktopTarget currentTarget() { return target; }
        @Override public Optional<DesktopFrame> pollFrame(int timeoutMillis) {
            polls++;
            if (captureFailure != null) throw captureFailure;
            if (next != null) captured = next;
            return Optional.ofNullable(next);
        }
        @Override public Optional<DesktopSurfaceSnapshot> currentSurface() {
            return proveSurface ? Optional.of(new DesktopSurfaceSnapshot("test", runtimeId,
                    polls > 0 && newSurfaceId != null ? newSurfaceId : "native-window", "window", "test.app",
                    captured.windowGeneration(), captured.contentRevision() + surfaceRevisionOffset,
                    captured.capturedAtMillis())) : Optional.empty();
        }
        @Override public DesktopActionResult perform(DesktopAction action, boolean foreground) {
            dispatches++;
            dispatched = action;
            return result;
        }
        @Override public void close() { }
    }
}
