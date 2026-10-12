package com.javaclaw.desktop.service;

import com.javaclaw.desktop.api.*;
import com.javaclaw.desktop.spi.DesktopPlatformProvider;
import com.javaclaw.desktop.spi.DesktopPlatformSession;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

class DesktopSessionCloseConcurrencyTest {
    @Test
    void cancelledCatalogReadCannotLeaveALatePendingObservation() throws Exception {
        var provider = new Provider();
        var owner = new DesktopSessionOwner("workspace", "scope", "chat", "request");
        long capturedAt = System.currentTimeMillis();
        Clock captureClock = Clock.fixed(java.time.Instant.ofEpochMilli(capturedAt), java.time.ZoneOffset.UTC);
        try (var service = new DefaultDesktopSessionService(List.of(provider),
                (who, target, purpose) -> true, captureClock)) {
            String id = service.open(owner, provider.target.id(), true).toCompletableFuture()
                    .get(3, TimeUnit.SECONDS).sessionId();
            provider.session.frames.add(new DesktopFrame(provider.target.id(), 1, capturedAt,
                    1, 1, 4, new byte[] {0, 0, 0, (byte) 255}));
            ManagedSession managed = service.sessions.get(id);
            await(() -> managed.latest != null);
            var previous = service.captureObservation(owner, id).toCompletableFuture()
                    .get(3, TimeUnit.SECONDS).orElseThrow();
            provider.session.catalogEntered = new CountDownLatch(1);
            provider.session.catalogRelease = new CountDownLatch(1);
            var cancelled = service.captureObservation(owner, id).toCompletableFuture();
            assertTrue(provider.session.catalogEntered.await(3, TimeUnit.SECONDS));
            assertTrue(cancelled.cancel(true), "cancellation wins while the catalog is still being read");
            provider.session.catalogRelease.countDown();
            await(() -> {
                synchronized (managed) { return managed.pendingObservation == null; }
            });
            assertTrue(cancelled.isCancelled());
            assertFalse(service.commitObservation(owner, id, previous.observationId())
                    .toCompletableFuture().get(3, TimeUnit.SECONDS),
                    "neither the replaced baseline nor a late cancelled baseline can be committed");

            provider.session.catalogEntered = null;
            provider.session.catalogRelease = null;
            var fresh = service.captureObservation(owner, id).toCompletableFuture()
                    .get(3, TimeUnit.SECONDS).orElseThrow();
            assertNotEquals(previous.observationId(), fresh.observationId());
            assertTrue(service.commitObservation(owner, id, fresh.observationId(), List.of())
                    .toCompletableFuture().get(3, TimeUnit.SECONDS));
        } finally {
            if (provider.session.catalogRelease != null) provider.session.catalogRelease.countDown();
        }
    }

    @Test
    void observationContinuationCanWaitForCommitWithoutHoldingTheTargetCoordinator() throws Exception {
        var provider = new Provider();
        var owner = new DesktopSessionOwner("workspace", "scope", "chat", "request");
        long capturedAt = System.currentTimeMillis();
        Clock captureClock = Clock.fixed(java.time.Instant.ofEpochMilli(capturedAt), java.time.ZoneOffset.UTC);
        try (var service = new DefaultDesktopSessionService(List.of(provider),
                (who, target, purpose) -> true, captureClock)) {
            String id = service.open(owner, provider.target.id(), true).toCompletableFuture()
                    .get(3, TimeUnit.SECONDS).sessionId();
            provider.session.frames.add(new DesktopFrame(provider.target.id(), 1, capturedAt,
                    1, 1, 4, new byte[] {0, 0, 0, (byte) 255}));
            await(() -> service.sessions.get(id).latest != null);
            provider.session.catalogEntered = new CountDownLatch(1);
            provider.session.catalogRelease = new CountDownLatch(1);
            var capture = service.captureObservation(owner, id).toCompletableFuture();
            assertTrue(provider.session.catalogEntered.await(3, TimeUnit.SECONDS));
            var committed = capture.thenApply(observation -> service.commitObservation(owner, id,
                    observation.orElseThrow().observationId(), List.of()).toCompletableFuture()
                    .orTimeout(3, TimeUnit.SECONDS).join());
            provider.session.catalogRelease.countDown();
            assertTrue(committed.get(5, TimeUnit.SECONDS),
                    "completion callbacks must run after the capture releases the target coordinator");
        } finally {
            if (provider.session.catalogRelease != null) provider.session.catalogRelease.countDown();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void oldObservationCleanupPreservesANewerPendingAndCommittedObservation(boolean commitOld)
            throws Exception {
        var provider = new Provider();
        var owner = new DesktopSessionOwner("workspace", "scope", "chat", "request");
        long capturedAt = System.currentTimeMillis();
        Clock captureClock = Clock.fixed(java.time.Instant.ofEpochMilli(capturedAt), java.time.ZoneOffset.UTC);
        try (var service = new DefaultDesktopSessionService(List.of(provider),
                (who, target, purpose) -> true, captureClock)) {
            String id = service.open(owner, provider.target.id(), true, DesktopInputPolicy.SYSTEM_EXPLICIT)
                    .toCompletableFuture().get(3, TimeUnit.SECONDS).sessionId();
            provider.session.frames.add(new DesktopFrame(provider.target.id(), 1, capturedAt,
                    1, 1, 4, new byte[] {0, 0, 0, (byte) 255}));
            ManagedSession managed = service.sessions.get(id);
            await(() -> managed.latest != null);
            var old = service.captureObservation(owner, id).toCompletableFuture()
                    .get(3, TimeUnit.SECONDS).orElseThrow();
            if (commitOld) assertTrue(service.commitObservation(owner, id, old.observationId(), List.of())
                    .toCompletableFuture().get(3, TimeUnit.SECONDS));
            var newer = service.captureObservation(owner, id).toCompletableFuture()
                    .get(3, TimeUnit.SECONDS).orElseThrow();
            var stateBeforeCleanup = service.state(owner, id);

            service.releaseForeground(owner, id, old.observationId()).toCompletableFuture()
                    .get(3, TimeUnit.SECONDS);
            synchronized (managed) {
                assertTrue(managed.foregroundLease, "old cleanup cannot restore focus owned by a newer capture");
                assertEquals(newer.observationId(), managed.pendingObservation.observationId());
                if (commitOld) assertEquals(old.observationId(), managed.committedObservation.observationId(),
                        "matching an older committed ID must not erase the different pending ID");
            }
            assertEquals(stateBeforeCleanup, service.state(owner, id));
            assertEquals(0, provider.session.restoreCount.get());
            assertTrue(service.commitObservation(owner, id, newer.observationId(), List.of())
                    .toCompletableFuture().get(3, TimeUnit.SECONDS));

            service.releaseForeground(owner, id, old.observationId()).toCompletableFuture()
                    .get(3, TimeUnit.SECONDS);
            service.releaseForeground(owner, id, "").toCompletableFuture().get(3, TimeUnit.SECONDS);
            synchronized (managed) {
                assertTrue(managed.foregroundLease);
                assertEquals(newer.observationId(), managed.committedObservation.observationId());
            }
            assertEquals(stateBeforeCleanup, service.state(owner, id));
            assertEquals(0, provider.session.restoreCount.get());

            service.releaseForeground(owner, id, newer.observationId()).toCompletableFuture()
                    .get(3, TimeUnit.SECONDS);
            synchronized (managed) {
                assertFalse(managed.foregroundLease);
                assertNull(managed.pendingObservation);
                assertNull(managed.committedObservation);
            }
            assertEquals(1, provider.session.restoreCount.get(), "the matching cleanup still releases its lease");
            assertEquals(DesktopSessionState.Kind.FOREGROUND_READY, service.state(owner, id).kind());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void closingDuringTrackedInputDoesNotInvertServiceAndTargetLocks(boolean manual) throws Exception {
        var provider = new Provider();
        var owner = new DesktopSessionOwner("workspace", "scope", "chat", "request");
        var service = new DefaultDesktopSessionService(List.of(provider), (who, target, purpose) -> true);
        CompletableFuture<Void> closing = null;
        try {
            String id = service.open(owner, provider.target.id(), true).toCompletableFuture().get(3, TimeUnit.SECONDS)
                    .sessionId();
            String lease = manual ? service.acquireManualControl(owner, id).toCompletableFuture()
                    .get(3, TimeUnit.SECONDS) : null;
            provider.session.frames.add(new DesktopFrame(provider.target.id(), 1, System.currentTimeMillis(),
                    1, 1, 4, new byte[] {0, 0, 0, (byte) 255}));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (service.sessions.get(id).latest == null && System.nanoTime() < deadline) Thread.sleep(10);
            var observation = service.captureObservation(owner, id).toCompletableFuture().get(3, TimeUnit.SECONDS)
                    .orElseThrow();
            assertTrue(service.commitObservation(owner, id, observation.observationId(), List.of())
                    .toCompletableFuture().get(3, TimeUnit.SECONDS));
            service.beginWindowAction(owner, id, "invocation", observation.observationId());
            assertTrue(observation.capturedSurface() != null,
                    "the fixture supplies a captured identity so actualOutcome queues window tracking");
            var action = new DesktopAction(DesktopAction.Kind.CLICK, 0, 0, 1, 1, 0, "", 1,
                    observation.observationId(), observation.elements().getFirst().id(), 0);
            var performing = (manual ? service.performManual(owner, id, lease, action)
                    : service.perform(owner, id, action)).toCompletableFuture();
            assertTrue(provider.session.entered.await(3, TimeUnit.SECONDS));
            closing = CompletableFuture.runAsync(() -> service.closeSession(owner, id));
            deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (service.sessions.containsKey(id) && System.nanoTime() < deadline) Thread.sleep(10);
            assertFalse(service.sessions.containsKey(id), "close owns the service lock and waits for native input");
            provider.session.release.countDown();
            closing.get(3, TimeUnit.SECONDS);
            assertEquals(DesktopActionResult.Status.VERIFIED, performing.get(3, TimeUnit.SECONDS).status());
        } finally {
            provider.session.release.countDown();
            // A failing lock-order regression must not block the entire test JVM in cleanup.
            if (closing == null || closing.isDone()) service.close();
        }
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
        final LinkedBlockingQueue<DesktopFrame> frames = new LinkedBlockingQueue<>();
        final CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        final AtomicInteger restoreCount = new AtomicInteger();
        volatile CountDownLatch catalogEntered;
        volatile CountDownLatch catalogRelease;
        volatile DesktopFrame captured;
        Session(DesktopTarget target) { this.target = target; }
        @Override public DesktopTarget currentTarget() { return target; }
        @Override public Optional<DesktopFrame> pollFrame(int timeoutMillis) {
            try {
                DesktopFrame frame = frames.poll(timeoutMillis, TimeUnit.MILLISECONDS);
                if (frame != null) captured = frame;
                return Optional.ofNullable(frame);
            } catch (InterruptedException stopped) { Thread.currentThread().interrupt(); return Optional.empty(); }
        }
        @Override public Optional<DesktopSurfaceSnapshot> currentSurface() {
            DesktopFrame frame = captured;
            return frame == null ? Optional.empty() : Optional.of(new DesktopSurfaceSnapshot("test", "runtime",
                    "surface", target.id(), target.applicationId(), frame.windowGeneration(),
                    frame.contentRevision(), frame.capturedAtMillis()));
        }
        @Override public List<DesktopElement> elements(DesktopFrame frame) {
            if (catalogEntered != null) catalogEntered.countDown();
            if (catalogRelease != null) {
                try { assertTrue(catalogRelease.await(3, TimeUnit.SECONDS)); }
                catch (InterruptedException stopped) { Thread.currentThread().interrupt(); throw new AssertionError(stopped); }
            }
            return List.of(new DesktopElement("e1", "button", "Action", 0, 0, 1, 1, DesktopElement.PRESS));
        }
        @Override public void prepareForeground() { }
        @Override public void restoreForeground() { restoreCount.incrementAndGet(); }
        @Override public DesktopActionResult perform(DesktopAction action, boolean foreground) {
            entered.countDown();
            try { assertTrue(release.await(5, TimeUnit.SECONDS)); }
            catch (InterruptedException stopped) { Thread.currentThread().interrupt(); throw new AssertionError(stopped); }
            return new DesktopActionResult(DesktopActionResult.Status.VERIFIED, "done", 1);
        }
        @Override public DesktopActionResult performClick(DesktopAction action, boolean foreground,
                com.javaclaw.desktop.spi.DesktopClickGuard guard) {
            return perform(action, foreground);
        }
        @Override public void close() { }
    }

    private static void await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(10);
        assertTrue(condition.getAsBoolean(), "condition did not become true within three seconds");
    }
}
