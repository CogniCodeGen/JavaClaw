package com.javaclaw.desktop;

import com.javaclaw.desktop.api.*;
import com.javaclaw.desktop.service.DefaultDesktopSessionService;
import com.javaclaw.desktop.spi.DesktopPlatformProvider;
import com.javaclaw.desktop.spi.DesktopPlatformSession;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DesktopVisualTargetBindingTest {
    private static final DesktopSessionOwner OWNER =
            new DesktopSessionOwner("workspace", "run", "agent", "request");
    private static final DesktopTarget TARGET = new DesktopTarget("fake", "window", 42,
            "项目看板", "项目看板", 0, 0, 128, 128, DesktopTarget.VISIBLE);

    @Test
    void visualTargetSurvivesUnrelatedAnimationAndUsesCurrentNativeRevisionOnlyOnce() {
        FakeProvider provider = new FakeProvider();
        try (var service = new DefaultDesktopSessionService(List.of(provider), allowAll())) {
            String id = service.open(OWNER, TARGET.id(), true).toCompletableFuture().join().sessionId();
            provider.session.emit(frame(1, -1, -1));
            await(() -> revision(service, id) == 1);
            DesktopObservation observed = capture(service, id);
            DesktopVisualRegion region = new DesktopVisualRegion(observed.observationId() + ":v0",
                    "tab", "待办", 16, 16, 16, 16, 0.95);
            assertTrue(service.commitObservation(OWNER, id, observed.observationId(), List.of(region))
                    .toCompletableFuture().join());

            provider.session.emit(frame(2, 110, 110));
            await(() -> revision(service, id) == 2);
            DesktopAction action = click(observed, region.id());
            DesktopActionResult result = service.perform(OWNER, id, action)
                    .toCompletableFuture().join();
            assertEquals(DesktopActionResult.Status.VERIFIED, result.status());
            assertEquals(1, provider.session.dispatches.get());
            assertEquals(2, provider.session.lastAction.contentRevision(),
                    "native must validate the current frame revision after local pixel comparison");
            assertEquals(region.centerX(), provider.session.lastAction.x());
            assertEquals(region.centerY(), provider.session.lastAction.y());
            assertEquals(DesktopActionResult.Status.STALE_FRAME,
                    service.perform(OWNER, id, action).toCompletableFuture().join().status());
            assertEquals(1, provider.session.dispatches.get(), "observation is single-use");
        }
    }

    @Test
    void changedVisualTargetAndForeignIdCannotDispatchAndRequireNewObservation() {
        FakeProvider provider = new FakeProvider();
        try (var service = new DefaultDesktopSessionService(List.of(provider), allowAll())) {
            String id = service.open(OWNER, TARGET.id(), true).toCompletableFuture().join().sessionId();
            provider.session.emit(frame(1, -1, -1));
            await(() -> revision(service, id) == 1);
            DesktopObservation observed = capture(service, id);
            DesktopVisualRegion region = new DesktopVisualRegion(observed.observationId() + ":v0",
                    "tab", "待办", 16, 16, 16, 16, 0.95);
            assertTrue(service.commitObservation(OWNER, id, observed.observationId(), List.of(region))
                    .toCompletableFuture().join());
            assertEquals(DesktopActionResult.Status.STALE_FRAME,
                    service.perform(OWNER, id, click(observed, "other-observation:v0"))
                            .toCompletableFuture().join().status());
            assertEquals(0, provider.session.dispatches.get());

            provider.session.emit(frame(2, 24, 24));
            await(() -> revision(service, id) == 2);
            DesktopActionResult stale = service.perform(OWNER, id, click(observed, region.id()))
                    .toCompletableFuture().join();
            assertEquals(DesktopActionResult.Status.STALE_FRAME, stale.status());
            assertEquals(DesktopActionResult.NextStep.OBSERVE, stale.nextStep());
            assertEquals(0, provider.session.dispatches.get());

            // Even if the old pixels reappear, the stale observation stays invalidated.
            provider.session.emit(frame(3, -1, -1));
            await(() -> revision(service, id) == 3);
            assertEquals(DesktopActionResult.Status.STALE_FRAME,
                    service.perform(OWNER, id, click(observed, region.id()))
                            .toCompletableFuture().join().status());
            assertEquals(0, provider.session.dispatches.get());
        }
    }

    @Test
    void invalidVisualCatalogAndLegacyCommitCannotUnlockUnknown() {
        FakeProvider provider = new FakeProvider();
        try (var service = new DefaultDesktopSessionService(List.of(provider), allowAll())) {
            String id = service.open(OWNER, TARGET.id(), true).toCompletableFuture().join().sessionId();
            provider.session.emit(frame(1, -1, -1));
            await(() -> revision(service, id) == 1);
            DesktopObservation observed = capture(service, id);
            DesktopVisualRegion forged = new DesktopVisualRegion("other:v0", "tab", "待办",
                    16, 16, 16, 16, 0.95);
            assertFalse(service.commitObservation(OWNER, id, observed.observationId(), List.of(forged))
                    .toCompletableFuture().join());
            assertTrue(service.commitObservation(OWNER, id, observed.observationId(), List.of())
                    .toCompletableFuture().join());

            provider.session.nextResult = new DesktopActionResult(
                    DesktopActionResult.Status.UNKNOWN, "maybe sent", 1);
            DesktopActionResult unknown = service.perform(OWNER, id, click(observed, ""))
                    .toCompletableFuture().join();
            assertEquals(DesktopActionResult.Status.UNKNOWN, unknown.status());
            assertEquals(1, provider.session.dispatches.get());
            provider.session.emit(frame(2, 110, 110, System.currentTimeMillis() + 250));
            await(() -> revision(service, id) == 2);
            DesktopObservation afterUnknown = capture(service, id);
            assertFalse(service.commitObservation(OWNER, id, afterUnknown.observationId())
                    .toCompletableFuture().join(), "legacy calls do not prove visual interpretation");
            assertEquals(DesktopActionResult.Status.DENIED,
                    service.perform(OWNER, id, click(afterUnknown, ""))
                            .toCompletableFuture().join().status());
            assertEquals(1, provider.session.dispatches.get());
            assertFalse(service.commitObservation(OWNER, id, afterUnknown.observationId(), List.of(forged))
                    .toCompletableFuture().join(), "a foreign visual catalog cannot refresh the input baseline");
            assertEquals(DesktopActionResult.Status.DENIED,
                    service.perform(OWNER, id, click(afterUnknown, ""))
                            .toCompletableFuture().join().status());
            assertEquals(1, provider.session.dispatches.get());
            assertTrue(service.commitObservation(OWNER, id, afterUnknown.observationId(), List.of())
                    .toCompletableFuture().join());
            provider.session.nextResult = new DesktopActionResult(
                    DesktopActionResult.Status.ACCEPTED, "new input accepted", 1);
            assertEquals(DesktopActionResult.Status.STALE_FRAME,
                    service.perform(OWNER, id, click(observed, ""))
                            .toCompletableFuture().join().status(),
                    "refreshing the input baseline must not make the old observation reusable");
            assertEquals(1, provider.session.dispatches.get());
            assertEquals(DesktopActionResult.Status.UNKNOWN, unknown.status(),
                    "the previous business effect remains unknown after input baseline refresh");
            assertEquals(DesktopActionResult.Status.ACCEPTED,
                    service.perform(OWNER, id, click(afterUnknown, ""))
                            .toCompletableFuture().join().status(),
                    "a new action can use the complete interpreted post-input baseline");
            assertEquals(2, provider.session.dispatches.get());
        }
    }

    @Test
    void captureGapInvalidatesOldObservationEvenWhenSamePixelsReturn() {
        FakeProvider provider = new FakeProvider();
        try (var service = new DefaultDesktopSessionService(List.of(provider), allowAll())) {
            String id = service.open(OWNER, TARGET.id(), true).toCompletableFuture().join().sessionId();
            provider.session.emit(frame(1, -1, -1));
            await(() -> revision(service, id) == 1);
            DesktopObservation observed = capture(service, id);
            assertTrue(service.commitObservation(OWNER, id, observed.observationId(), List.of())
                    .toCompletableFuture().join());

            provider.session.current = new DesktopTarget("fake", "window", 42,
                    "项目看板", "项目看板", 0, 0, 128, 128, DesktopTarget.MINIMIZED);
            await(() -> service.state(OWNER, id).kind() == DesktopSessionState.Kind.PAUSED
                    && service.state(OWNER, id).detail().contains("最小化"));
            provider.session.current = TARGET;
            provider.session.emit(frame(2, -1, -1));
            await(() -> revision(service, id) == 2);
            assertEquals(DesktopActionResult.Status.STALE_FRAME,
                    service.perform(OWNER, id, click(observed, ""))
                            .toCompletableFuture().join().status());
            assertEquals(0, provider.session.dispatches.get());
        }
    }

    private static DesktopConsentPort allowAll() { return (owner, target, purpose) -> true; }

    private static DesktopAction click(DesktopObservation observed, String targetId) {
        return new DesktopAction(DesktopAction.Kind.CLICK, 0, 0, 1, 1, 0, "",
                observed.frame().windowGeneration(), observed.observationId(), targetId, 0);
    }

    private static DesktopObservation capture(DefaultDesktopSessionService service, String id) {
        return service.captureObservation(OWNER, id).toCompletableFuture().join().orElseThrow();
    }

    private static long revision(DefaultDesktopSessionService service, String id) {
        return service.snapshot(OWNER, id).toCompletableFuture().join()
                .map(DesktopFrame::contentRevision).orElse(-1L);
    }

    private static DesktopFrame frame(long revision, int changedX, int changedY) {
        return frame(revision, changedX, changedY, System.currentTimeMillis());
    }

    private static DesktopFrame frame(long revision, int changedX, int changedY, long capturedAt) {
        byte[] pixels = new byte[128 * 128 * 4];
        for (int i = 3; i < pixels.length; i += 4) pixels[i] = (byte) 255;
        if (changedX >= 0) pixels[(changedY * 128 + changedX) * 4] = 100;
        return new DesktopFrame(TARGET.id(), 1, capturedAt, 128, 128, 128 * 4,
                pixels, revision);
    }

    private static void await(BooleanSupplier condition) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) return;
            try { Thread.sleep(10); }
            catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new AssertionError(interrupted);
            }
        }
        assertTrue(condition.getAsBoolean());
    }

    private static final class FakeProvider implements DesktopPlatformProvider {
        final FakeSession session = new FakeSession();

        @Override public String id() { return "fake"; }
        @Override public DesktopAvailability probe() {
            return new DesktopAvailability(true, "fake", DesktopAvailability.CAPTURE, "ready");
        }
        @Override public List<DesktopTarget> discoverTargets() { return List.of(TARGET); }
        @Override public DesktopPlatformSession open(DesktopTarget target) { return session; }
    }

    private static final class FakeSession implements DesktopPlatformSession {
        final BlockingQueue<DesktopFrame> frames = new LinkedBlockingQueue<>();
        final AtomicInteger dispatches = new AtomicInteger();
        volatile DesktopTarget current = TARGET;
        volatile DesktopAction lastAction;
        volatile DesktopActionResult nextResult = new DesktopActionResult(
                DesktopActionResult.Status.VERIFIED, "done", 1);

        void emit(DesktopFrame frame) { frames.add(frame); }
        @Override public DesktopTarget currentTarget() { return current; }
        @Override public Optional<DesktopFrame> pollFrame(int timeoutMillis) {
            try { return Optional.ofNullable(frames.poll(timeoutMillis, TimeUnit.MILLISECONDS)); }
            catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return Optional.empty();
            }
        }
        @Override public DesktopActionResult perform(DesktopAction action, boolean foreground) {
            lastAction = action;
            dispatches.incrementAndGet();
            return nextResult;
        }
        @Override public void prepareForeground() { }
        @Override public void restoreForeground() { }
        @Override public void close() { }
    }
}
