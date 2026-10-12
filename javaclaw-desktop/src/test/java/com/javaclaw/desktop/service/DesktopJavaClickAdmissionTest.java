package com.javaclaw.desktop.service;

import com.javaclaw.desktop.api.DesktopAction;
import com.javaclaw.desktop.api.DesktopActionResult;
import com.javaclaw.desktop.api.DesktopAvailability;
import com.javaclaw.desktop.api.DesktopElement;
import com.javaclaw.desktop.api.DesktopFrame;
import com.javaclaw.desktop.api.DesktopFrameGeometry;
import com.javaclaw.desktop.api.DesktopInputPolicy;
import com.javaclaw.desktop.api.DesktopObservation;
import com.javaclaw.desktop.api.DesktopSessionOwner;
import com.javaclaw.desktop.api.DesktopSurfaceSnapshot;
import com.javaclaw.desktop.api.DesktopTarget;
import com.javaclaw.desktop.spi.DesktopPlatformProvider;
import com.javaclaw.desktop.spi.DesktopPlatformSession;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DesktopJavaClickAdmissionTest {
    @Test void refreshRejectsAChangedRegionBeforePlatformInput() throws Exception {
        try (var fixture = new Fixture()) {
            DesktopObservation baseline = fixture.observe();
            fixture.provider.session.changeRegion = true;
            DesktopActionResult result = fixture.service.perform(fixture.owner, fixture.sessionId,
                    fixture.click(baseline, 1)).toCompletableFuture().get(3, TimeUnit.SECONDS);
            assertEquals(DesktopActionResult.Status.STALE_FRAME, result.status());
            assertEquals(DesktopActionResult.Delivery.NOT_SENT, result.delivery());
            assertEquals(0, fixture.provider.session.dispatches);
            assertFalse(fixture.service.pendingInputs.containsKey(fixture.managed.targetKey));
        }
    }

    @Test void capturePermissionRefusalCannotCreateAnUncertainInputBarrier() throws Exception {
        try (var fixture = new Fixture()) {
            DesktopObservation baseline = fixture.observe();
            fixture.provider.session.captureDenied = true;
            DesktopActionResult result = fixture.service.perform(fixture.owner, fixture.sessionId,
                    fixture.click(baseline, 1)).toCompletableFuture().get(3, TimeUnit.SECONDS);
            assertEquals(DesktopActionResult.Status.DENIED, result.status());
            assertEquals(DesktopActionResult.Delivery.NOT_SENT, result.delivery());
            assertEquals(0, fixture.provider.session.dispatches);
            assertFalse(fixture.service.pendingInputs.containsKey(fixture.managed.targetKey));
        }
    }

    @Test void exceptionDuringDoubleClickDeliveryRemainsUnknownAndCannotReplay() throws Exception {
        try (var fixture = new Fixture(DesktopInputPolicy.SYSTEM_EXPLICIT)) {
            DesktopObservation baseline = fixture.observe();
            assertTrue(fixture.managed.foregroundGranted);
            assertTrue(fixture.managed.foregroundLease, "the double click requires a normal observed focus lease");
            fixture.provider.session.deliveryFailure = true;
            DesktopAction click = fixture.click(baseline, 2);
            DesktopActionResult result = fixture.service.perform(fixture.owner, fixture.sessionId, click)
                    .toCompletableFuture().get(3, TimeUnit.SECONDS);
            assertEquals(DesktopActionResult.Status.UNKNOWN, result.status());
            assertEquals(DesktopActionResult.Delivery.MAYBE_SENT, result.delivery());
            assertEquals(DesktopActionResult.Mode.FOREGROUND_SYNTHETIC, result.mode());
            assertEquals(1, fixture.provider.session.restores);
            assertTrue(fixture.service.pendingInputs.containsKey(fixture.managed.targetKey));
            DesktopActionResult replay = fixture.service.perform(fixture.owner, fixture.sessionId, click)
                    .toCompletableFuture().get(3, TimeUnit.SECONDS);
            assertEquals(DesktopActionResult.Status.DENIED, replay.status());
            assertEquals(DesktopActionResult.Delivery.NOT_SENT, replay.delivery());
            assertEquals(1, fixture.provider.session.dispatches);
        }
    }

    private static final class Fixture implements AutoCloseable {
        final Provider provider = new Provider();
        final DesktopSessionOwner owner = new DesktopSessionOwner("workspace", "scope", "chat", "request");
        final DefaultDesktopSessionService service = new DefaultDesktopSessionService(List.of(provider),
                (who, target, purpose) -> true);
        final String sessionId;
        final ManagedSession managed;
        Fixture() throws Exception { this(DesktopInputPolicy.BACKGROUND_STRICT); }
        Fixture(DesktopInputPolicy policy) throws Exception {
            sessionId = service.open(owner, provider.target.id(), true, policy).toCompletableFuture()
                    .get(3, TimeUnit.SECONDS).sessionId();
            managed = service.sessions.get(sessionId);
            provider.session.managed = managed;
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (managed.latest == null && System.nanoTime() < deadline) Thread.sleep(10);
            assertNotNull(managed.latest);
        }
        DesktopObservation observe() throws Exception {
            Optional<DesktopObservation> captured = Optional.empty();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (captured.isEmpty() && System.nanoTime() < deadline) {
                captured = service.captureObservation(owner, sessionId).toCompletableFuture()
                        .get(3, TimeUnit.SECONDS);
                if (captured.isEmpty()) Thread.sleep(10);
            }
            DesktopObservation observation = captured.orElseThrow();
            assertTrue(service.commitObservation(owner, sessionId, observation.observationId(), List.of())
                    .toCompletableFuture().get(3, TimeUnit.SECONDS));
            return observation;
        }
        DesktopAction click(DesktopObservation observation, int clicks) {
            return new DesktopAction(DesktopAction.Kind.CLICK, 0, 0, 1, clicks, 0, "", 3,
                    observation.observationId(), observation.elements().getFirst().id(), 7);
        }
        @Override public void close() { service.close(); }
    }

    private static final class Provider implements DesktopPlatformProvider {
        final DesktopTarget target = new DesktopTarget("test", "window", 10, "App", "Window",
                0, 0, 1, 1, DesktopTarget.VISIBLE, "test.app");
        final Session session = new Session(target);
        @Override public String id() { return "test"; }
        @Override public DesktopAvailability probe() { return new DesktopAvailability(true, "test", 1, "ready"); }
        @Override public List<DesktopTarget> discoverTargets() { return List.of(target); }
        @Override public DesktopPlatformSession open(DesktopTarget requested) { return session; }
    }

    private static final class Session implements DesktopPlatformSession {
        final DesktopTarget target;
        volatile ManagedSession managed;
        DesktopFrame captured;
        volatile boolean changeRegion;
        volatile boolean captureDenied;
        volatile boolean deliveryFailure;
        int dispatches;
        int restores;
        Session(DesktopTarget target) { this.target = target; }
        @Override public DesktopTarget currentTarget() { return target; }
        @Override public synchronized Optional<DesktopFrame> pollFrame(int timeoutMillis) {
            boolean admission = managed != null && Thread.holdsLock(managed);
            if (admission && captureDenied) throw new SecurityException("capture denied");
            captured = new DesktopFrame(target.id(), 3, System.currentTimeMillis(), 1, 1, 4,
                    new byte[] {(byte) (admission && changeRegion ? 1 : 0), 0, 0, (byte) 255},
                    admission ? 8 : 7, new DesktopFrameGeometry(1, 1, 0, 0, 1, 1, true));
            return Optional.of(captured);
        }
        @Override public synchronized Optional<DesktopSurfaceSnapshot> currentSurface() {
            return captured == null ? Optional.empty() : Optional.of(new DesktopSurfaceSnapshot("test",
                    "process-instance", "native-window", target.id(), target.applicationId(),
                    captured.windowGeneration(), captured.contentRevision(), captured.capturedAtMillis()));
        }
        @Override public List<DesktopElement> elements(DesktopFrame frame) {
            return List.of(new DesktopElement("button", "button", "Action", 0, 0, 1, 1, DesktopElement.PRESS));
        }
        @Override public void prepareForeground() { }
        @Override public void restoreForeground() { restores++; }
        @Override public DesktopActionResult perform(DesktopAction action, boolean foreground) {
            dispatches++;
            if (deliveryFailure) throw new IllegalStateException("second click delivery unconfirmed");
            return new DesktopActionResult(DesktopActionResult.Status.ACCEPTED, "sent", action.windowGeneration());
        }
        @Override public void close() { }
    }
}
